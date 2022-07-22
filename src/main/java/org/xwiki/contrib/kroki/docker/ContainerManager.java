/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.kroki.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.AsyncDockerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.SyncDockerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

/**
 * Help perform various operations on Docker containers.
 *
 * @version $Id: 92e825542a069d9030e3d95d60492835ddb4c24d $
 * @since 1.0
 */
@Component(roles = ContainerManager.class)
@Singleton
public class ContainerManager implements Initializable
{
    private static final String DOCKER_SOCK = "/var/run/docker.sock";

    @Inject
    private Logger logger;

    private DockerClient client;

    @Override
    public void initialize() throws InitializationException
    {
        this.logger.debug("Initializing the Docker client.");
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient =
            new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
                .build();
        this.client = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * Attempts to reuse the container with the specified name.
     *
     * @param containerName the name of the container to reuse
     * @param reuse {@code true} to reuse the container if found, {@code false} to remove it if found
     * @return the container id, if a container with the specified name exists and can be reused, otherwise {@code null}
     */
    public String maybeReuseContainerByName(String containerName, boolean reuse)
    {
        this.logger.debug("Looking for an existing Docker container with name [{}].", containerName);

        List<Container> containers = exec(
            this.client.listContainersCmd().withNameFilter(Collections.singletonList(containerName)).withShowAll(true));

        if (!containers.isEmpty()) {
            containers = containers.stream().filter(container -> container.getNames()[0].equals("/" + containerName))
                .collect(Collectors.toList());
        }

        if (containers.isEmpty()) {
            this.logger.debug("Could not find any Docker container with name [{}].", containerName);
            // There's no container with the specified name.
            return null;
        }

        InspectContainerResponse container = inspectContainer(containers.get(0).getId());
        if (!reuse) {
            this.logger.debug("Docker container [{}] found but we can't reuse it.", container.getId());
            try {
                removeContainer(container);
            } catch (Exception e) {
                this.logger.debug("Error thrown in remove container [{}].", container.getId());
            }
            return null;
        } else if (container.getState().getDead() == Boolean.TRUE) {
            this.logger.debug("Docker container [{}] is dead. Removing it.", container.getId());
            // The container is not reusable. Try to remove it so it can be recreated.
            removeContainer(container.getId());
            return null;
        } else if (container.getState().getPaused() == Boolean.TRUE) {
            this.logger.debug("Docker container [{}] is paused. Unpausing it.", container.getId());
            exec(this.client.unpauseContainerCmd(container.getId()));
        } else if (container.getState().getRunning() != Boolean.TRUE
            && container.getState().getRestarting() != Boolean.TRUE)
        {
            this.logger.debug("Docker container [{}] is neither running nor restarting. Starting it.",
                container.getId());
            this.startContainer(container.getId());
        }

        return container.getId();
    }

    /**
     * Checks if a given image is already pulled locally.
     *
     * @param imageName the image to check
     * @return {@code true} if the specified image was already pulled, {@code false} otherwise
     */
    public boolean isLocalImagePresent(String imageName)
    {
        try {
            exec(this.client.inspectImageCmd(imageName));
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /**
     * Pull the specified Docker image.
     *
     * @param imageName the image to pull
     */
    public void pullImage(String imageName)
    {
        this.logger.debug("Pulling the Docker image [{}].", imageName);
        wait(this.client.pullImageCmd(imageName));
    }

    /**
     * Creates a new container based on the specified image.
     *
     * @param imageName the image to use for the new container
     * @param containerName the name to associate with the created container
     * @param parameters the parameters to specify when creating the container
     * @param hostConfig the host configuration
     * @return the id of the created container
     */
    public String createContainer(String imageName, String containerName, List<String> parameters,
        HostConfig hostConfig)
    {
        this.logger.debug("Creating a Docker container with name [{}] using image [{}] and having parameters [{}].",
            containerName, imageName, parameters);

        // set extra hosts if network == bridge (using host.xwiki.internal:host-gateway)

        try (CreateContainerCmd createContainerCmd = this.client.createContainerCmd(imageName)) {
            List<ExposedPort> exposedPorts = new ArrayList<>(hostConfig.getPortBindings().getBindings().keySet());
            CreateContainerResponse container =
                createContainerCmd.withName(containerName).withCmd(parameters).withExposedPorts(exposedPorts)
                    .withHostConfig(hostConfig).exec();
            this.logger.debug("Created the Docker container with id [{}].", container.getId());
            return container.getId();
        }
    }

    /**
     * Creates the host configuration.
     *
     * @param network the network to join
     * @param port the port to expose (the port the container is going to listen to)
     * @return the host configuration
     */
    public HostConfig getHostConfig(String network, int port)
    {
        ExposedPort exposedPort = ExposedPort.tcp(port);
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindPort(port));

        return HostConfig.newHostConfig().withAutoRemove(true).withNetworkMode(network).withBinds(
            // Make sure it also works when XWiki is running in Docker.
            new Bind(DOCKER_SOCK, new Volume(DOCKER_SOCK))).withPortBindings(portBindings);
    }

    /**
     * Start the specified container.
     *
     * @param containerId the id of the container to start
     */
    public void startContainer(String containerId)
    {
        this.logger.debug("Starting the Docker container with id [{}].", containerId);
        exec(this.client.startContainerCmd(containerId));
    }

    /**
     * Stop the specified container.
     *
     * @param containerId the if of the container to stop
     */
    public void stopContainer(String containerId)
    {
        this.logger.debug("Stopping the Docker container with id [{}].", containerId);
        exec(this.client.stopContainerCmd(containerId));

        // Wait for the container to be fully stopped before continuing.
        this.logger.debug("Wait for the Docker container [{}] to stop.", containerId);
        try {
            wait(this.client.waitContainerCmd(containerId));
        } catch (NotFoundException e) {
            // Do nothing (the container might have been removed automatically when stopped).
            this.logger.debug("container [{}] might have been removed automatically when stopped.", containerId);
        }
    }

    /**
     * @param containerId the container id
     * @param networkIdOrName the network id or name
     * @return the IP address of the specified container in the specified network
     */
    public String getIpAddress(String containerId, String networkIdOrName)
    {
        Map<String, ContainerNetwork> networks = inspectContainer(containerId).getNetworkSettings().getNetworks();
        // Try to find the network by name.
        ContainerNetwork network = networks.get(networkIdOrName);
        if (network == null) {
            // Otherwise, find the network by id. Throw an exception if not found.
            network = networks.values().stream().filter(n -> n.getNetworkID().equals(networkIdOrName)).findFirst()
                .orElse(null);
            if (network == null) {
                throw new NullPointerException();
            }
        }
        return network.getIpAddress();
    }

    /**
     * Remove the specified container.
     *
     * @param containerId the if of the container to remove
     */
    private void removeContainer(String containerId)
    {
        try {
            this.logger.debug("Removing the Docker container with id [{}].", containerId);
            exec(this.client.removeContainerCmd(containerId));
        } catch (NotFoundException e) {
            // Do nothing (the container might have been removed automatically when stopped).
        }
    }

    private void removeContainer(InspectContainerResponse container)
    {
        if (container.getState().getPaused() == Boolean.TRUE) {
            exec(this.client.unpauseContainerCmd(container.getId()));
            stopContainer(container.getId());
        } else if (container.getState().getRunning() == Boolean.TRUE
            || container.getState().getRestarting() == Boolean.TRUE)
        {
            stopContainer(container.getId());
        }
        removeContainer(container.getId());
    }

    /**
     * Inspect the specified container to get more information.
     *
     * @param containerId the container to inspect
     * @return information about the specified container
     */
    private InspectContainerResponse inspectContainer(String containerId)
    {
        this.logger.debug("Inspecting the Docker container [{}].", containerId);
        return exec(this.client.inspectContainerCmd(containerId));
    }

    private void wait(AsyncDockerCmd<?, ?> command)
    {
        // Can't write simply try(command) because spoon complains about "Variable resource not allowed here for source
        // level below 9".
        try (AsyncDockerCmd<?, ?> cmd = command) {
            cmd.start().awaitCompletion();
        } catch (InterruptedException e) {
            this.logger.warn("Interrupted thread [{}]. Root cause: [{}].", Thread.currentThread().getName(),
                ExceptionUtils.getRootCauseMessage(e));
            // Restore the interrupted state.
            Thread.currentThread().interrupt();
        }
    }

    private <T> T exec(SyncDockerCmd<T> command)
    {
        // Can't write simply try(command) because spoon complains about "Variable resource not allowed here for source
        // level below 9".
        try (SyncDockerCmd<T> cmd = command) {
            return cmd.exec();
        }
    }
}

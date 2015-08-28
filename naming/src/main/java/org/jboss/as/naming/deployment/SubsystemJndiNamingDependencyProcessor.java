/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.as.naming.deployment;

import org.jboss.as.naming.service.DefaultNamespaceContextSelectorService;
import org.jboss.as.naming.service.NamingService;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.*;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a service that depends on a subset of the JNDI bindings. Necessary as WeldBootstrapService cannot depend on
 * the JndiNamingDependencyProcessor as this would cause a cyclic dependency - JNDI depends on BeanManagerService which
 * depends on WeldBootstrapService.
 * <p/>
 * As binding services are not children of the root deployment unit service this service
 * is necessary to ensure the deployment is not considered complete until add bindings are up
 *
 * @author Stuart Douglas
 * @author Ryan Emerson
 */
public class SubsystemJndiNamingDependencyProcessor implements DeploymentUnitProcessor {

    private static final ServiceName SUBSYSTEM_JNDI_DEPENDENCY_SERVICE = ServiceName.of("subsystemJndiDependencyService");


    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {

        //this will always be up but we need to make sure the naming service is
        //not shut down before the deployment is undeployed when the container is shut down
        phaseContext.addToAttachmentList(Attachments.NEXT_PHASE_DEPS, NamingService.SERVICE_NAME);

        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        List<ServiceName> dependencies = getAllowedDependencies();
        final ServiceName serviceName = serviceName(deploymentUnit.getServiceName());
        final ServiceBuilder<?> serviceBuilder = phaseContext.getServiceTarget().addService(serviceName, new RuntimeBindReleaseService());
        serviceBuilder.addDependencies(dependencies);
        if(deploymentUnit.getParent() != null) {
            serviceBuilder.addDependencies(deploymentUnit.getParent().getAttachment(Attachments.JNDI_DEPENDENCIES));
        }
        serviceBuilder.addDependency(NamingService.SERVICE_NAME);
        serviceBuilder.addDependency(DefaultNamespaceContextSelectorService.SERVICE_NAME);
        serviceBuilder.install();
    }

    public static ServiceName serviceName(final ServiceName deploymentUnitServiceName) {
        return deploymentUnitServiceName.append(SUBSYSTEM_JNDI_DEPENDENCY_SERVICE);
    }

    public static ServiceName serviceName(final DeploymentUnit deploymentUnit) {
        return serviceName(deploymentUnit.getServiceName());
    }

    public static List<ServiceName> getAllowedDependencies() {
        List<ServiceName> dependencies = new ArrayList<>();
        dependencies.add(ContextNames.JBOSS_CONTEXT_SERVICE_NAME.append(ServiceName.of("UserTransaction")));
        dependencies.add(ContextNames.JBOSS_CONTEXT_SERVICE_NAME.append(ServiceName.of("TransactionSynchronizationRegistry")));
        dependencies.add(ContextNames.JBOSS_CONTEXT_SERVICE_NAME.append(ServiceName.of("TransactionManager")));
        return dependencies;
    }

    @Override
    public void undeploy(final DeploymentUnit context) {
    }

}

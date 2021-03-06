/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.wildfly.extension.mod_cluster;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.transform.TransformationContext;
import org.jboss.as.controller.transform.description.DefaultCheckersAndConverter;
import org.jboss.dmr.ModelNode;

import java.util.Map;

class SessionDrainingStrategyChecker extends DefaultCheckersAndConverter {

    static final SessionDrainingStrategyChecker INSTANCE = new SessionDrainingStrategyChecker();

    @Override
    public String getRejectionLogMessage(Map<String, ModelNode> attributes) {
        return ModClusterLogger.ROOT_LOGGER.sessionDrainingStrategyMustBeUndefinedOrDefault();
    }

    @Override
    protected boolean rejectAttribute(PathAddress address, String attributeName, ModelNode attributeValue, TransformationContext context) {
        return attributeValue.isDefined() && (checkForExpression(attributeValue) || !attributeValue.asString().equals("DEFAULT"));
    }

    @Override
    protected void convertAttribute(PathAddress address, String attributeName, ModelNode attributeValue, TransformationContext context) {
        // No conversion needed
    }

    @Override
    protected boolean isValueDiscardable(PathAddress address, String attributeName, ModelNode attributeValue, TransformationContext context) {
        return attributeValue.isDefined() && !checkForExpression(attributeValue) && attributeValue.asString().equals("DEFAULT");
    }
}

//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.embedded;

import java.io.IOException;
import java.util.EnumSet;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.cdi.CdiConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@Disabled("waiting on weld to release weld-servlet-core with jakarta namespace")
public class EmbeddedWeldTest
{
    public static Server createServerWithServletContext(String mode)
    {
        Server server = new Server();
        server.addConnector(new LocalConnector(server));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setResourceBase("src/test/resources/weldtest");
        server.setHandler(context);

        // Setup context
        context.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(GreetingsServlet.class, "/");
        context.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        // Setup Jetty weld integration
        /*
        TODO: uncomment when Weld 4 is used.
        switch (mode)
        {
            case "none" : // Do nothing, let weld work it out.
                // Expect:INFO: WELD-ENV-001201: Jetty 7.2+ detected, CDI injection will be available in Servlets and Filters. Injection into Listeners is not supported.
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "DecoratingListener+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new org.eclipse.jetty.webapp.DecoratingListener(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "CdiDecoratingListener+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addEventListener(new org.eclipse.jetty.cdi.CdiDecoratingListener(context));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiSpiDecorator+Listener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.getObjectFactory().addDecorator(new org.eclipse.jetty.cdi.CdiSpiDecorator(context));
                context.getServletHandler().addListener(new ListenerHolder(org.jboss.weld.environment.servlet.Listener.class));
                break;

            case "CdiServletContainerInitializer+Listener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiServletContainerInitializer(CdiDecoratingListener)+Listener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addEventListener(new org.jboss.weld.environment.servlet.Listener());
                break;

            case "CdiServletContainerInitializer+EnhancedListener":
                // Expect:INFO: WELD-ENV-001213: Jetty CDI SPI support detected, CDI injection will be available in Listeners, Servlets and Filters.
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                break;

            case "CdiServletContainerInitializer(CdiDecoratingListener)+EnhancedListener":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                break;

            case "EnhancedListener+CdiServletContainerInitializer(CdiDecoratingListener)":
                // Expect:INFO: WELD-ENV-001212: Jetty CdiDecoratingListener support detected, CDI injection will be available in Listeners, Servlets and Filters
                context.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
                context.addBean(new ServletContextHandler.Initializer(context, new org.jboss.weld.environment.servlet.EnhancedListener()));
                context.addBean(new ServletContextHandler.Initializer(context, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
                break;
        }

         */

        return server;
    }

    @ParameterizedTest()
    @ValueSource(strings =
        {
            "none",
            "DecoratingListener+Listener",
            "CdiDecoratingListener+Listener",
            "CdiSpiDecorator+Listener",
            "CdiServletContainerInitializer+Listener",
            "CdiServletContainerInitializer(CdiDecoratingListener)+Listener",
            "CdiServletContainerInitializer+EnhancedListener",
            "CdiServletContainerInitializer(CdiDecoratingListener)+EnhancedListener"
        })
    public void testServletContext(String mode) throws Exception
    {
        Server server = createServerWithServletContext(mode);
        server.start();
        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        if (mode.contains("EnhancedListener"))
            assertThat(response, containsString("Listener saw Weld BeanManager"));
        else
            assertThat(response, containsString("Listener saw null"));

        assertThat(response, containsString("Beans from Weld BeanManager for "));

        server.stop();
    }

    @Test
    public void testWebappContext() throws Exception
    {
        Server server = new Server(8080);
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setResourceBase("src/test/resources/weldtest");
        server.setHandler(webapp);

        webapp.setInitParameter(org.eclipse.jetty.cdi.CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, org.eclipse.jetty.cdi.CdiDecoratingListener.MODE);
        webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.eclipse.jetty.cdi.CdiServletContainerInitializer()));
        // TODO: uncomment with Weld 4.
        // webapp.addBean(new ServletContextHandler.Initializer(webapp, new org.jboss.weld.environment.servlet.EnhancedListener()));

        webapp.getServerClassMatcher().add("-org.eclipse.jetty.embedded.");
        webapp.getSystemClassMatcher().add("org.eclipse.jetty.embedded.");

        webapp.addServlet(GreetingsServlet.class, "/");
        webapp.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        assertThat(response, containsString("Listener saw Weld BeanManager"));
        server.stop();
    }

    @Test
    public void testWebappContextDiscovered() throws Exception
    {
        Server server = new Server(8080);
        server.addConnector(new LocalConnector(server));
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setResourceBase("src/test/resources/weldtest");
        server.setHandler(webapp);

        // Need the AnnotationConfiguration to detect SCIs
        webapp.addConfiguration(new AnnotationConfiguration());

        // Need to expose our SCI.  This is ugly could be made better in jetty-10 with a CdiConfiguration
        webapp.addConfiguration(new CdiConfiguration());

        // This is ugly but needed for maven for testing in a overlaid war pom
        webapp.getServerClassMatcher().add("-org.eclipse.jetty.embedded.");
        webapp.getSystemClassMatcher().add("org.eclipse.jetty.embedded.");

        webapp.getServletHandler().addListener(new ListenerHolder(MyContextListener.class));
        webapp.addFilter(MyFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        webapp.addServlet(GreetingsServlet.class, "/");

        server.start();

        LocalConnector connector = server.getBean(LocalConnector.class);
        String response = connector.getResponse("GET / HTTP/1.0\r\n\r\n");
        System.err.println(response);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Hello GreetingsServlet filtered by Weld BeanManager "));
        assertThat(response, containsString("Beans from Weld BeanManager "));
        assertThat(response, containsString("Listener saw Weld BeanManager"));
        server.stop();

    }

    public static class MyContextListener implements ServletContextListener
    {
        @Inject
        BeanManager manager;

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            sce.getServletContext().setAttribute("listener", manager);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {

        }
    }

    public static class MyFilter implements Filter
    {
        @Inject
        BeanManager manager;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            if (manager == null)
                throw new IllegalStateException();
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            // copy attribute from MyListener to see if it was decorated.
            request.setAttribute("filter", manager);
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {

        }
    }

    public static class GreetingsServlet extends HttpServlet
    {
        @Inject
        @Named("friendly")
        public Greetings greetings;

        @Inject
        BeanManager manager;

        @Override
        public void init()
        {
            if (manager == null)
                throw new IllegalStateException();
        }

        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().print(greetings == null ? "NULL" : greetings.getGreeting());
            resp.getWriter().print(" filtered by ");
            resp.getWriter().println(req.getAttribute("filter"));
            resp.getWriter().println("Beans from " + manager);
            resp.getWriter().println("Listener saw " + req.getServletContext().getAttribute("listener"));
        }
    }

    public interface Greetings
    {
        String getGreeting();
    }

    public static class FriendlyGreetings
    {
        @Produces
        @Named("friendly")
        public Greetings friendly(InjectionPoint ip)
        {
            return () -> "Hello " + ip.getMember().getDeclaringClass().getSimpleName();
        }

        @Produces
        @Named("old")
        public Greetings old()
        {
            return () -> "Salutations!";
        }
    }

}

package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Objects;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

public class Main
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();

        // SSL Context Factory for HTTPS
        URL keystore = Objects.requireNonNull(Main.class.getResource("/keystore.p12"));
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(Paths.get(keystore.toURI()).toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustAll(true);
        sslContextFactory.setRenegotiationAllowed(false);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setProvider("Conscrypt");

        // HTTP Configuration
        HttpConfiguration https_config = new HttpConfiguration();

        // HTTPS Configuration
        SecureRequestCustomizer src = new SecureRequestCustomizer(); // Include SNI = multiple domains.
        https_config.addCustomizer(src);
        HttpConnectionFactory http = new HttpConnectionFactory(https_config);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(https_config);

        // Only HTTP/2.
        String protocol = h2.getProtocol();
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory(protocol, http.getProtocol());
        alpn.setDefaultProtocol(protocol);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // HTTP(s) connectors use ALPN.
        ServerConnector httpsConnector = new ServerConnector(server, ssl, alpn, h2, http);
        httpsConnector.setPort(8443);
        server.addConnector(httpsConnector);

        // Configure WebSocket.
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.addServlet(MainServlet.class, "/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((context, container) ->
            container.addMapping("/", (req, resp) -> new ProtocolEchoSocket())));

        server.start();
        System.err.println("https://demo:8443/");
        server.join();
    }

    @WebSocket
    public static class ProtocolEchoSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session) throws IOException
        {
            String protocol = session.getUpgradeRequest().getHttpVersion();
            session.getRemote().sendString("Upgraded over " + protocol);
        }

        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws IOException
        {
            session.getRemote().sendString(message);
        }
    }

    public static class MainServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            URL resource = Objects.requireNonNull(Main.class.getResource("/index.html"));
            InputStream inputStream = resource.openStream();
            resp.setContentType("text/html");
            IO.copy(inputStream, resp.getOutputStream());
        }
    }
}

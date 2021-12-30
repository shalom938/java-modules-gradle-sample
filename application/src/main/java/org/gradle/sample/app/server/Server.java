package org.gradle.sample.app.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.gradle.sample.messenger.Messenger;
import org.gradle.sample.utilities.HttpUtils;
import org.gradle.sample.utilities.MessageFormatter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.gradle.sample.app.Main.ADDR;
import static org.gradle.sample.app.Main.PORT;

public class Server {

    /*
    shutting down HttpServer: calling stop(delay) on HttpServer is not always sufficient to shut down the jvm too.
    assuming a simple HttpServer in a main method, after starting the server, and if no request was served,
    calling stop will stop the server and the jvm will exit. but if a request was made between start and stop
    the server will stop but the jvm will not exit because there are live threads in the server's thread pull.
    shutting down the thread pull before calling stop on HttpServer will fix that.
    another solution is making sure that the threads in the thread poll are daemon threads.
    this example takes the two approaches just for demonstration but each of them will work.
     */


    private final HttpServer server;
    private final List<HttpContext> contexts = new ArrayList<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "http-server-thread-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });

    public Server() throws IOException {
        this(ADDR, PORT);
    }

    public Server(String addr, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(addr, port), 0);
        server.setExecutor(executorService);
        contexts.add(server.createContext("/msgs", new MessagesHttpHandler()));
        contexts.add(server.createContext("/shutdown", new ShutdownHandler()));
    }


    public void start() {
        server.start();
    }


    public void stop() {
        contexts.forEach(server::removeContext);
        stopExecutionService();
        server.stop(1);
    }


    class MessagesHttpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Messenger messenger = new Messenger();
            var allMessages = messenger.getAllMessages();
            handleResponse(exchange, allMessages);
        }


        //todo: intellij doesn't see utilities as automatic modules.
        // builds fine with gradle and also builds fine with intellij but the editor shows errors
        // https://youtrack.jetbrains.com/issue/IDEA-183692

        private void handleResponse(HttpExchange httpExchange, Map<String, String> allMessages) throws IOException {

            OutputStream outputStream = httpExchange.getResponseBody();

            StringBuilder htmlBuilder = new StringBuilder();
            htmlBuilder.append("<html>").append("<body>");

            var queryParams = HttpUtils.getQueryParamMap(httpExchange.getRequestURI().getQuery());

            allMessages.forEach((k, v) -> {
                System.out.println("got message from ".concat(k).concat(", message: ").concat(v));

                htmlBuilder.append("<h1> ------------------ </h1>");
                htmlBuilder.append("<h1>");

                if ("short".equals(queryParams.get("format"))) {
                    htmlBuilder.append(k + ":" + MessageFormatter.messagePreview(v));
                } else {
                    htmlBuilder.append(k + ":" + v);
                }

                htmlBuilder.append("</h1>");
            });

            htmlBuilder.append("</body>");
            htmlBuilder.append("</html>");

            //// encode HTML content
            ////String htmlResponse = StringEscapeUtils.escapeHtml4(htmlBuilder.toString());
            byte[] htmlResponse = htmlBuilder.toString().getBytes();
            // this line is a must
            httpExchange.sendResponseHeaders(200, htmlResponse.length);
            outputStream.write(htmlResponse);
            outputStream.flush();
            outputStream.close();
        }
    }


    class ShutdownHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "Shutting down..";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
            exchange.close();

            new Thread(new Runnable() {
                @Override
                public void run() {

                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }

                    stopExecutionService();

                    System.out.println("Stopping Server...");
                    exchange.getHttpContext().getServer().stop(1);
                    System.out.println("Server Stopped!");
                }
            }).start();
        }
    }


    private void stopExecutionService() {
        System.out.println("Stopping executor service...");
        executorService.shutdown();
        try {
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        executorService.shutdownNow();
        System.out.println("Executor service stopped.");
    }


}

package co.edu.escuelaing.reflexionlab;

import co.edu.escuelaing.reflexionlab.annotations.GetMapping;
import co.edu.escuelaing.reflexionlab.annotations.RequestParam;
import co.edu.escuelaing.reflexionlab.annotations.RestController;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class MicroSpringBoot {

    public static Map<String, Method> controllerMethods = new HashMap<>();
    public static Map<String, Object> controllerInstances = new HashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("Loading rest controllers and their methods...");

        if (args.length > 0) {
            for (String className : args) {
                loadController(className);
            }
        } else {
            scanAndLoad();
        }

        ServerSocket serverSocket = new ServerSocket(8080);
        System.out.println("Server started on http://localhost:8080");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            handleRequest(clientSocket);
        }
    }

    private static void loadController(String className) throws Exception {
        Class c = Class.forName(className);

        if (c.isAnnotationPresent(RestController.class)) {
            Object instance = c.getDeclaredConstructor().newInstance();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(GetMapping.class)) {
                    GetMapping a = m.getAnnotation(GetMapping.class);
                    String path = a.value();
                    controllerMethods.put(path, m);
                    controllerInstances.put(path, instance);
                }
            }
        }
    }

    private static void scanAndLoad() throws Exception {
        String classpath = System.getProperty("java.class.path");
        for (String entry : classpath.split(File.pathSeparator)) {
            File root = new File(entry);
            if (root.isDirectory()) {
                for (File f : listClasses(root)) {
                    String name = root.toURI().relativize(f.toURI()).getPath()
                            .replace("/", ".").replace(".class", "");
                    try {
                        Class c = Class.forName(name);
                        if (c.isAnnotationPresent(RestController.class)) {
                            loadController(name);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static java.util.List<File> listClasses(File dir) {
        java.util.List<File> result = new java.util.ArrayList<>();
        for (File f : dir.listFiles() != null ? dir.listFiles() : new File[0]) {
            if (f.isDirectory()) result.addAll(listClasses(f));
            else if (f.getName().endsWith(".class")) result.add(f);
        }
        return result;
    }

    private static void handleRequest(Socket clientSocket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        OutputStream out = clientSocket.getOutputStream();

        String requestLine = in.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            clientSocket.close();
            return;
        }

        System.out.println("Request: " + requestLine);

        String fullUri = requestLine.split(" ")[1];
        String path = fullUri.contains("?") ? fullUri.substring(0, fullUri.indexOf('?')) : fullUri;
        String query = fullUri.contains("?") ? fullUri.substring(fullUri.indexOf('?') + 1) : "";

        if ("/".equals(path) && staticResourceExists("/index.html")) {
            serveStaticFile("/index.html", out);
            clientSocket.close();
            return;
        }

        if (controllerMethods.containsKey(path)) {
            Method m = controllerMethods.get(path);
            Object instance = controllerInstances.get(path);
            Object[] methodArgs = resolveArgs(m, query);
            String body = (String) m.invoke(instance, methodArgs);
            sendResponse(out, 200, "text/plain", body);
        } else {
            serveStaticFile(path, out);
        }

        clientSocket.close();
    }

    private static boolean staticResourceExists(String path) {
        try (InputStream resource = MicroSpringBoot.class.getResourceAsStream("/webroot" + path)) {
            return resource != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Object[] resolveArgs(Method m, String query) {
        Map<String, String> params = parseQuery(query);
        Parameter[] parameters = m.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(RequestParam.class)) {
                RequestParam rp = parameters[i].getAnnotation(RequestParam.class);
                args[i] = params.getOrDefault(rp.value(), rp.defaultValue());
            }
        }
        return args;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static void serveStaticFile(String path, OutputStream out) throws Exception {
        InputStream resource = MicroSpringBoot.class.getResourceAsStream("/webroot" + path);
        if (resource == null) {
            sendResponse(out, 404, "text/plain", "404 Not Found: " + path);
            return;
        }
        byte[] bytes = resource.readAllBytes();
        String mime = path.endsWith(".html") ? "text/html" :
                      path.endsWith(".css")  ? "text/css"  :
                      path.endsWith(".js")   ? "application/javascript" :
                      path.endsWith(".png")  ? "image/png" : "application/octet-stream";
        String header = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(bytes);
        out.flush();
    }

    private static void sendResponse(OutputStream out, int status, String contentType, String body) throws Exception {
        String statusText = status == 200 ? "OK" : "Not Found";
        byte[] bytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(bytes);
        out.flush();
    }
}

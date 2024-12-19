package org.acme.health;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A utility class to check IP reachability and the health status of an
 * application.
 */
public class IpHealthChecker {

    private static final int TIMEOUT = 5000; // 5 seconds timeout

    /**
     * Checks if the given IP address is reachable and if the health endpoint is UP.
     *
     * @param ip The IP address to check.
     * @return true if the IP is reachable and the health endpoint is UP, false
     *         otherwise.
     */
    public boolean isIpReachableAndHealthy(String ip) {
        // if (!isNetworkReachable(ip)) {
        // System.err.printf("IP %s is not reachable.%n", ip);
        // return false;
        // }

        if (!isHealthEndpointUp(ip)) {
            System.err.printf("Health endpoint at IP %s is not UP.%n", ip);
            return false;
        }

        System.out.printf("IP %s is reachable, and the health endpoint is UP.%n", ip);
        return true;
    }

    /**
     * Checks if the given IP address is reachable within a timeout.
     *
     * @param ip The IP address to check.
     * @return true if the IP is reachable, false otherwise.
     */
    // private boolean isNetworkReachable(String ip) {
    // try {
    // InetAddress address = InetAddress.getByName(ip);
    // System.out.printf("Checking network reachability for IP %s...%n", ip);
    // return address.isReachable(TIMEOUT);
    // } catch (Exception e) {
    // System.err.printf("Network check failed for IP %s: %s%n", ip,
    // e.getMessage());
    // return false;
    // }
    // }

    /**
     * Checks if the health endpoint of the application at the given IP is UP.
     *
     * @param ip The IP address of the application.
     * @return true if the health endpoint returns UP, false otherwise.
     */
    private boolean isHealthEndpointUp(String ip) {
        try {
            // Build the health endpoint URI
            URI healthUri = new URI("http", null, ip, 8080, "/q/health", null, null);
            URL healthUrl = healthUri.toURL();

            System.out.printf("Checking health endpoint at %s...%n", healthUrl);

            HttpURLConnection connection = (HttpURLConnection) healthUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                // Parse the JSON response
                String response = new String(connection.getInputStream().readAllBytes());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonResponse = mapper.readTree(response);
                String status = jsonResponse.get("status").asText();
                return "UP".equalsIgnoreCase(status);
            } else {
                System.err.printf("Unexpected response code %d for IP %s%n", responseCode, ip);
            }
        } catch (Exception e) {
            System.err.printf("Health endpoint check failed for IP %s: %s%n", ip, e.getMessage());
        }

        return false;
    }
}

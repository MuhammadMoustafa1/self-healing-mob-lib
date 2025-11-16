package com.fawry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fawry.utilities.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AIIntegrationService {

    private static final String QWENMOE_API_URL = "http://10.100.55.98:8660/v1/chat/completions";
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public AIIntegrationService() {
        this.httpClient = new OkHttpClient.Builder().connectTimeout(30L, TimeUnit.SECONDS).readTimeout(60L, TimeUnit.SECONDS).build();
        this.mapper = new ObjectMapper();
    }

    public String analyzeAndGenerateXPaths(String damagedLocator, String xmlSnapshotPath) {
        try {
            String xmlSnapshotContent = readFileContent(xmlSnapshotPath);
            if (xmlSnapshotContent == null) xmlSnapshotContent = "";

            Log.info("XML Snapshot content sent to AI model:\n" + xmlSnapshotContent);
            String prompt = createAnalysisPrompt(damagedLocator, xmlSnapshotContent);
            Log.info("Sending request to AI model with prompt:\n" + prompt);
            String aiResponse = callQwenMoeAPI(prompt);
            String locator = extractLocatorFromAIResponse(aiResponse);

            if (locator != null && !locator.isEmpty()) {
                Log.info("Generated locator from AI analysis: " + locator);
                return locator;
            } else {
                Log.info("Failed to extract valid locator from AI response");
                return null;
            }
        } catch (Exception e) {
            Log.error("AI analysis failed", e);
            return null;
        }
    }

    private String callQwenMoeAPI(String prompt) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", "./qwenmoe/content/qwenmoe/");
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        Request request = new Request.Builder().url(QWENMOE_API_URL).post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json"))).addHeader("Authorization", "Bearer EMPTY").addHeader("Content-Type", "application/json").build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "empty body";
                int statusCode = response.code();
                Log.info("API request failed. Status: " + statusCode + ", Body: " + errorBody);
                throw new IOException("Unexpected response: " + response);
            } else {
                String responseBody = response.body().string();
                Log.info("Raw API response: " + responseBody);
                JsonNode root = mapper.readTree(responseBody);
                String aiResponse = root.path("choices").path(0).path("message").path("content").asText().trim();
                Log.info("AI model response: " + aiResponse);
                return aiResponse;
            }
        }
    }

    private String readFileContent(String filePath) {
        Path path = Paths.get(filePath);
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append(System.lineSeparator());
            return content.toString().trim();
        } catch (IOException e) {
            Log.error("Unable to read file " + filePath, e);
            return null;
        }
    }

    private String createAnalysisPrompt(String damagedLocator, String xmlSnapshot) {
        boolean hasIOS = damagedLocator.contains("XCUIElementType") || damagedLocator.contains("name") || damagedLocator.contains("label") || damagedLocator.contains("value");
        boolean hasAndroid = damagedLocator.contains("android.widget") || damagedLocator.contains("resource-id") || damagedLocator.contains("@text") || damagedLocator.contains("By.id");
        String platform = hasIOS && !hasAndroid ? "iOS" : hasAndroid && !hasIOS ? "Android" : "Mixed (Android and iOS)";

        String listBuilder = "1. " + damagedLocator + "\n";

        String rules = "- For the damaged locator, return ONLY the single best/corrected locator (no explanations).\n"
                + "- Example:\n"
                + "  Input: By.id(\"Create New Accounttttttt\")\n"
                + "  Corrected Output: By.id(\"Create New Account\")and  the see xmlSnapshot to generate the most correct text\n"
                + "- Do NOT join multiple XPaths with '|'.\n"
                + "- For the damaged locator (By.id, By.className, By.cssSelector, By.tagName, By.linkText, By.partialLinkText, XPath), find the closest match in the XML snapshot.\n"
                + "- If an exact match exists, use it. Otherwise, generate corrected locator using flexible attribute matches (contains(), starts-with(), normalize-space()).\n"
                + "- Prefer attributes based on platform:\n"
                + "  * iOS → @name, @label, @value\n"
                + "  * Android → @resource-id, @content-desc, @text\n"
                + "    - Special case: if the Android locator starts with 'com.fawry.retailer:id/', preserve the full ID and return it as By.id(\"com.fawry.retailer:id/...\") without converting to XPath.\n"
                + "- Preserve relationships (sibling, parent, ancestor) if present.\n"
                + "- Handle numbers in attribute values and node names correctly.\n"
                + "- Be case-insensitive when matching attribute values and node names.\n"
                + "- Support mixed Android and iOS locators in the same input.\n"
                + "- Support locators like By.cssSelector, By.tagName, By.linkText, By.partialLinkText, or other custom attributes.\n"
                + "- If a locator contains a word in the middle, you may remove it if needed to find a match.\n"
                + "- You may change letters at the start or in the middle of attribute values to find the closest match.\n"
                + "- If a locator contains an index (e.g., [2]), keep the index unchanged in the output.\n"
                + "- Normalize spaces, tabs, or newlines in attributes before matching.\n"
                + "- If the tag name changes slightly (e.g., Button → TextView), still return the closest valid locator.\n";

        String learningHint = String.format("MODEL LEARNING CONTEXT:\n" + "- Platform: %s\n" + "- Learn from the XML snapshot structure and attribute usage.\n" + "- One-shot learning example for special Android IDs:\n" + "  Input Locator: By.id(\"com.fawry.retailer:id/balance_amount_edit_view\")\n" + "  Corrected Locator: By.id(\"com.fawry.retailer:id/balance_amount_edit_view\")\n" + "- Apply this pattern to all 'com.fawry.retailer:id/' locators.\n", platform);

        return String.format("ROLE: You are an advanced automation test assistant specializing in self-healing mobile locators.\n" + "PLATFORM: %s\n%s\n" + "TASK: Given a damaged locator (XPath, By.id, sibling axes, name, etc.) and the current XML snapshot, search inside the XML and return the corrected locator.\n\n" + "INPUT:\n1. Damaged Locator:\n%s\n\n2. Current XML Snapshot:\n'''\n%s\n'''\n\nRULES:\n%s", platform, learningHint, listBuilder.trim(), xmlSnapshot, rules);
    }

    public String extractLocatorFromAIResponse(String response) {
        String cleanedResponse = response.replaceAll("^```[a-zA-Z]*", "")
                .replaceAll("```$", "")
                .trim();

        for (String line : cleanedResponse.split("\\r?\\n")) {
            String locator = line.trim();
            if (!locator.isEmpty()) {
                Log.info("Extracted Locator: " + locator);
                return locator; // Return the first valid locator
            }
        }

        Log.info("AI response doesn't contain valid locators: " + response);
        return null;
    }

    public String autoAnalyzeAndFix(String damagedLocator) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String xmlSnapshotPath = "xml_snapshots/snapshot_" + timestamp + ".xml";
        return analyzeAndGenerateXPaths(damagedLocator, xmlSnapshotPath);
    }
}
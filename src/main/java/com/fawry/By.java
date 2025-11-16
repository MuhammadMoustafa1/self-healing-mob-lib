package com.fawry;

import com.fawry.utilities.Log;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class By extends org.openqa.selenium.By {

    private static AppiumDriver driver;
    private final org.openqa.selenium.By originalBy;
    private static final Map<String, org.openqa.selenium.By> healedCache = new ConcurrentHashMap<>();
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    private By(org.openqa.selenium.By by) {
        this.originalBy = by;
    }

    // Factory methods
    public static By xpath(String xpath) { return new By(org.openqa.selenium.By.xpath(xpath)); }
    public static By id(String id) { return new By(org.openqa.selenium.By.id(id)); }
    public static By name(String name) { return new By(org.openqa.selenium.By.name(name)); }
    public static By cssSelector(String selector) { return new By(org.openqa.selenium.By.cssSelector(selector)); }
    public static By className(String className) { return new By(org.openqa.selenium.By.className(className)); }
    public static By tagName(String tagName) { return new By(org.openqa.selenium.By.tagName(tagName)); }
    public static By linkText(String linkText) { return new By(org.openqa.selenium.By.linkText(linkText)); }
    public static By partialLinkText(String partialLinkText) { return new By(org.openqa.selenium.By.partialLinkText(partialLinkText)); }

    public static void setDriver(AppiumDriver appiumDriver) {
        driver = appiumDriver;
    }

    @Override
    public WebElement findElement(SearchContext context) {
        String locatorKey = this.originalBy.toString();

        try {
            if (!HealingContext.isHealingEnabled()) {
                return this.originalBy.findElement(context);
            }

            if (healedCache.containsKey(locatorKey)) {
                return healedCache.get(locatorKey).findElement(context);
            }

            // Normal explicit wait before triggering healing
            WebDriverWait wait = new WebDriverWait(driver, DEFAULT_WAIT);
            return wait.until(ExpectedConditions.presenceOfElementLocated(this.originalBy));

        } catch (TimeoutException | InvalidElementStateException | NoSuchElementException e) {
            Log.info("⚠️ Element not found after wait for locator: " + locatorKey);
            Log.info("🔁 Attempting healing...");

            // Trigger healing process after normal wait timeout
            org.openqa.selenium.By healedBy = this.healLocator(locatorKey);
            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful. Cached healed locator: " + healedBy);

                WebDriverWait wait = new WebDriverWait(driver, DEFAULT_WAIT);
                return wait.until(ExpectedConditions.presenceOfElementLocated(healedBy));
            }

            throw new NoSuchElementException("❌ Failed to heal locator: " + locatorKey, e);
        }
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        String locatorKey = this.originalBy.toString();

        try {
            if (!HealingContext.isHealingEnabled()) {
                return this.originalBy.findElements(context);
            }

            if (healedCache.containsKey(locatorKey)) {
                return healedCache.get(locatorKey).findElements(context);
            }

            WebDriverWait wait = new WebDriverWait(driver, DEFAULT_WAIT);
            return wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(this.originalBy));

        } catch (TimeoutException | InvalidElementStateException | NoSuchElementException e) {
            Log.info("⚠️ Elements not found after wait for locator: " + locatorKey);
            Log.info("🔁 Attempting healing...");

            org.openqa.selenium.By healedBy = this.healLocator(locatorKey);
            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful for elements. Cached healed locator: " + healedBy);

                WebDriverWait wait = new WebDriverWait(driver, DEFAULT_WAIT);
                return wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(healedBy));
            }

            throw new NoSuchElementException("❌ Failed to heal elements for locator: " + locatorKey, e);
        }
    }

    /**
     * Heal locator using AIIntegrationService
     */
    private org.openqa.selenium.By healLocator(String rawLocator) {
        try {
            if (driver == null) {
                Log.info("❌ AppiumDriver not set. Cannot capture XML for healing.");
                return null;
            }

            Log.info("🤖 Healing locator: " + rawLocator);

            String cleanedLocator = rawLocator
                    .replace("By.xpath: ", "")
                    .replace("By.id: ", "")
                    .replace("By.name: ", "")
                    .replace("By.cssSelector: ", "")
                    .replace("By.className: ", "")
                    .replace("By.tagName: ", "")
                    .replace("By.linkText: ", "")
                    .replace("By.partialLinkText: ", "")
                    .trim();

            // Generate XML snapshot
            XmlGenerator xmlGenerator = new XmlGenerator();
            xmlGenerator.setDriver(driver);
            xmlGenerator.clearXmlSnapshotsDirectory();
            xmlGenerator.generatePageXML();

            // Call AI to analyze and fix locator
            String healedLocator = new AIIntegrationService().autoAnalyzeAndFix(cleanedLocator);
            if (healedLocator != null && !healedLocator.isEmpty()) {
                Log.info("🌐 AI returned healed locator: " + healedLocator);

                if (healedLocator.startsWith("By.id(")) {
                    String value = healedLocator.substring(7, healedLocator.length() - 2);
                    return org.openqa.selenium.By.id(value);
                } else if (healedLocator.startsWith("By.xpath(")) {
                    String value = healedLocator.substring(9, healedLocator.length() - 2);
                    return org.openqa.selenium.By.xpath(value);
                } else if (healedLocator.startsWith("By.name(")) {
                    String value = healedLocator.substring(8, healedLocator.length() - 2);
                    return org.openqa.selenium.By.name(value);
                } else if (healedLocator.startsWith("By.cssSelector(")) {
                    String value = healedLocator.substring(15, healedLocator.length() - 2);
                    return org.openqa.selenium.By.cssSelector(value);
                } else if (healedLocator.startsWith("By.className(")) {
                    String value = healedLocator.substring(13, healedLocator.length() - 2);
                    return org.openqa.selenium.By.className(value);
                } else if (healedLocator.startsWith("By.tagName(")) {
                    String value = healedLocator.substring(11, healedLocator.length() - 2);
                    return org.openqa.selenium.By.tagName(value);
                } else {
                    Log.info("⚠️ Unknown locator type, defaulting to XPath");
                    return org.openqa.selenium.By.xpath(healedLocator);
                }
            }
        } catch (Exception e) {
            Log.info("❌ Healing process failed for locator: " + rawLocator);
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String toString() {
        return "ByHealable(" + this.originalBy.toString() + ")";
    }
}

package com.getjobs.application.controller;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtensionErrorFilterTest {

    @Test
    void blocksKnownExtensionErrorsBeforeTheyReachTheNextDevelopmentOverlay() throws IOException {
        String filterScript = Files.readString(Path.of("front/public/ignore-extension-errors.js"));

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext()) {
            context.addInitScript(filterScript);
            Page page = context.newPage();
            page.navigate("data:text/html,<html><body>test</body></html>");
            page.evaluate("""
                    window.observedErrors = [];
                    window.addEventListener('error', event => window.observedErrors.push(event.filename));
                    window.dispatchEvent(new ErrorEvent('error', {
                      filename: 'chrome-extension://acfcbfkjgnbfglpnlfipdohfdgpgpogh/assets/content_mainworld.js',
                      error: new Error('addListener')
                    }));
                    window.dispatchEvent(new ErrorEvent('error', {
                      filename: 'http://localhost:6867/app.js',
                      error: new Error('application error')
                    }));
                    """);

            assertEquals(1, ((Number) page.evaluate("window.observedErrors.length")).intValue());
            assertEquals("http://localhost:6867/app.js", page.evaluate("window.observedErrors[0]"));
        }
    }
}

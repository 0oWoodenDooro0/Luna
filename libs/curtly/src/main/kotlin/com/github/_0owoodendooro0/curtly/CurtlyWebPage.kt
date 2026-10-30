package com.github._0owoodendooro0.curtly

import kotlinx.html.*
import kotlinx.html.stream.createHTML

object CurtlyWebPage {
    /**
     * Returns the HTML string of the built-in short URL creation web page generated using Kotlin HTML DSL.
     *
     * @param apiEndpoint The API endpoint URL to which the form sends POST requests. Defaults to "/api/shorten".
     * @param title The webpage title. Defaults to "Curtly - Short URL Generator".
     */
    fun getHtml(
        apiEndpoint: String = "/api/shorten",
        title: String = "Curtly - Short URL Generator"
    ): String {
        val sanitizedEndpoint = apiEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")

        return "<!DOCTYPE html>\n" + createHTML().html {
            lang = "en"
            head {
                meta { charset = "UTF-8" }
                meta {
                    name = "viewport"
                    content = "width=device-width, initial-scale=1.0"
                }
                title { +title }
                link {
                    rel = "stylesheet"
                    href = "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap"
                }
                style {
                    unsafe {
                        +cssStyles()
                    }
                }
            }
            body {
                div("container") {
                    div("header") {
                        div("badge-container") {
                            div("logo-badge") { +"CURTLY" }
                            div("audit-badge") { +"AUDIT & ANALYTICS READY" }
                        }
                        h1 { +title }
                        p("subtitle") { +"Create lightweight, customizable, and audit-tracked short links instantly." }
                    }

                    form(action = "#", method = FormMethod.post, classes = "card") {
                        id = "shorten-form"

                        div("form-group") {
                            label("form-label") {
                                htmlFor = "longUrl"
                                +"Destination URL "
                                span("required-star") { +"*" }
                            }
                            input(type = InputType.url, classes = "form-input") {
                                id = "longUrl"
                                name = "longUrl"
                                placeholder = "https://example.com/long-url"
                                required = true
                            }
                        }

                        div("form-row") {
                            div("form-group") {
                                label("form-label") {
                                    htmlFor = "customKey"
                                    +"Custom Key (Optional)"
                                }
                                input(type = InputType.text, classes = "form-input") {
                                    id = "customKey"
                                    name = "customKey"
                                    placeholder = "e.g. my-link"
                                }
                            }

                            div("form-group") {
                                label("form-label") {
                                    htmlFor = "expiresInSeconds"
                                    +"Expiration (Seconds)"
                                }
                                input(type = InputType.number, classes = "form-input") {
                                    id = "expiresInSeconds"
                                    name = "expiresInSeconds"
                                    placeholder = "e.g. 3600"
                                    min = "1"
                                }
                            }
                        }

                        div("form-group") {
                            label("form-label") {
                                htmlFor = "maxClicks"
                                +"Max Click Limit (Optional)"
                            }
                            input(type = InputType.number, classes = "form-input") {
                                id = "maxClicks"
                                name = "maxClicks"
                                placeholder = "e.g. 100"
                                min = "1"
                            }
                        }

                        button(type = ButtonType.submit, classes = "submit-btn") {
                            id = "submit-btn"
                            span {
                                id = "btn-text"
                                +"Create Short URL"
                            }
                            span("spinner") {
                                id = "loading-spinner"
                                style = "display: none;"
                            }
                        }
                    }

                    div("result-card") {
                        id = "result-box"
                        style = "display: none;"

                        div("result-header") {
                            span("result-title") { +"Short URL Created!" }
                        }
                        div("result-body") {
                            a(classes = "short-url-link") {
                                id = "short-url-link"
                                target = "_blank"
                                rel = "noopener noreferrer"
                            }
                            button(type = ButtonType.button, classes = "copy-btn") {
                                id = "copy-btn"
                                span {
                                    id = "copy-text"
                                    +"Copy"
                                }
                            }
                        }
                    }

                    div("error-card") {
                        id = "error-box"
                        style = "display: none;"

                        span("error-title") { +"Error" }
                        p {
                            id = "error-message"
                        }
                    }

                    div("footer") {
                        +"Powered by Curtly URL Shortener Library"
                    }
                }

                script {
                    unsafe {
                        +jsScript(sanitizedEndpoint)
                    }
                }
            }
        }
    }

    private fun cssStyles(): String = """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Inter', system-ui, -apple-system, sans-serif;
            background: #0f172a;
            color: #f8fafc;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 2rem 1rem;
        }
        .container {
            width: 100%;
            max-width: 560px;
            margin: 0 auto;
        }
        .header {
            text-align: center;
            margin-bottom: 2rem;
        }
        .badge-container {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
            margin-bottom: 0.75rem;
        }
        .logo-badge {
            display: inline-block;
            background: linear-gradient(135deg, #38bdf8, #6366f1);
            color: #ffffff;
            font-size: 0.75rem;
            font-weight: 700;
            letter-spacing: 0.1em;
            padding: 0.25rem 0.75rem;
            border-radius: 9999px;
        }
        .audit-badge {
            display: inline-block;
            background: rgba(16, 185, 129, 0.15);
            border: 1px solid #10b981;
            color: #34d399;
            font-size: 0.7rem;
            font-weight: 600;
            letter-spacing: 0.05em;
            padding: 0.2rem 0.6rem;
            border-radius: 9999px;
        }
        h1 {
            font-size: 1.875rem;
            font-weight: 700;
            color: #f8fafc;
            margin-bottom: 0.5rem;
        }
        .subtitle {
            color: #94a3b8;
            font-size: 0.95rem;
        }
        .card {
            background: #1e293b;
            border: 1px solid #334155;
            border-radius: 1rem;
            padding: 2rem;
            box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3), 0 8px 10px -6px rgba(0, 0, 0, 0.3);
        }
        .form-group {
            margin-bottom: 1.25rem;
        }
        .form-row {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 1rem;
        }
        @media (max-width: 480px) {
            .form-row { grid-template-columns: 1fr; }
        }
        .form-label {
            display: block;
            font-size: 0.875rem;
            font-weight: 500;
            color: #cbd5e1;
            margin-bottom: 0.5rem;
        }
        .required-star { color: #f43f5e; }
        .form-input {
            width: 100%;
            background: #0f172a;
            border: 1px solid #334155;
            border-radius: 0.5rem;
            padding: 0.75rem 1rem;
            color: #f8fafc;
            font-size: 0.95rem;
            transition: border-color 0.2s, box-shadow 0.2s;
        }
        .form-input:focus {
            outline: none;
            border-color: #38bdf8;
            box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.15);
        }
        .form-input::placeholder { color: #64748b; }
        .submit-btn {
            width: 100%;
            background: linear-gradient(135deg, #0ea5e9, #6366f1);
            color: #ffffff;
            font-weight: 600;
            font-size: 1rem;
            padding: 0.875rem;
            border: none;
            border-radius: 0.5rem;
            cursor: pointer;
            transition: transform 0.1s, opacity 0.2s, box-shadow 0.2s;
            display: flex;
            align-items: center;
            justify-content: center;
            margin-top: 0.5rem;
        }
        .submit-btn:hover {
            opacity: 0.95;
            box-shadow: 0 4px 12px rgba(14, 165, 233, 0.3);
        }
        .submit-btn:active { transform: scale(0.99); }
        .submit-btn:disabled {
            opacity: 0.6;
            cursor: not-allowed;
        }
        .spinner {
            width: 1.25rem;
            height: 1.25rem;
            border: 2px solid rgba(255, 255, 255, 0.3);
            border-top-color: #ffffff;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        .result-card {
            margin-top: 1.5rem;
            background: #064e3b;
            border: 1px solid #059669;
            border-radius: 0.75rem;
            padding: 1.25rem;
        }
        .result-header { margin-bottom: 0.5rem; }
        .result-title {
            font-weight: 600;
            font-size: 0.875rem;
            color: #34d399;
        }
        .result-body {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 0.75rem;
            background: #022c22;
            padding: 0.75rem 1rem;
            border-radius: 0.5rem;
            border: 1px solid #047857;
        }
        .short-url-link {
            color: #6ee7b7;
            font-weight: 600;
            text-decoration: none;
            word-break: break-all;
        }
        .short-url-link:hover { text-decoration: underline; }
        .copy-btn {
            background: #059669;
            color: #ffffff;
            border: none;
            border-radius: 0.375rem;
            padding: 0.5rem 0.875rem;
            font-size: 0.875rem;
            font-weight: 600;
            cursor: pointer;
            white-space: nowrap;
            transition: background-color 0.2s;
        }
        .copy-btn:hover { background: #10b981; }
        .error-card {
            margin-top: 1.5rem;
            background: #450a0a;
            border: 1px solid #dc2626;
            border-radius: 0.75rem;
            padding: 1.25rem;
            color: #fca5a5;
        }
        .error-title {
            font-weight: 700;
            font-size: 0.875rem;
            color: #f87171;
            display: block;
            margin-bottom: 0.25rem;
        }
        .footer {
            text-align: center;
            margin-top: 2rem;
            font-size: 0.8rem;
            color: #64748b;
        }
    """.trimIndent()

    private fun jsScript(apiEndpoint: String): String = """
        document.addEventListener('DOMContentLoaded', () => {
            const form = document.getElementById('shorten-form');
            const submitBtn = document.getElementById('submit-btn');
            const btnText = document.getElementById('btn-text');
            const loadingSpinner = document.getElementById('loading-spinner');
            const resultBox = document.getElementById('result-box');
            const shortUrlLink = document.getElementById('short-url-link');
            const copyBtn = document.getElementById('copy-btn');
            const copyText = document.getElementById('copy-text');
            const errorBox = document.getElementById('error-box');
            const errorMessage = document.getElementById('error-message');
            const API_ENDPOINT = "$apiEndpoint";

            form.addEventListener('submit', async (e) => {
                e.preventDefault();
                resultBox.style.display = 'none';
                errorBox.style.display = 'none';

                const longUrl = document.getElementById('longUrl').value.trim();
                const customKey = document.getElementById('customKey').value.trim();
                const expiresInSeconds = document.getElementById('expiresInSeconds').value;
                const maxClicks = document.getElementById('maxClicks').value;

                if (!longUrl) return;

                const body = { longUrl: longUrl };
                if (customKey) body.customKey = customKey;
                if (expiresInSeconds) body.expiresInSeconds = parseInt(expiresInSeconds, 10);
                if (maxClicks) body.maxClicks = parseInt(maxClicks, 10);

                submitBtn.disabled = true;
                btnText.style.display = 'none';
                loadingSpinner.style.display = 'inline-block';

                try {
                    const res = await fetch(API_ENDPOINT, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(body)
                    });

                    if (!res.ok) {
                        let errText = 'Failed to shorten URL';
                        try {
                            const errJson = await res.json();
                            errText = errJson.message || errJson.error || errText;
                        } catch (_) {
                            const text = await res.text();
                            if (text) errText = text;
                        }
                        throw new Error(errText);
                    }

                    const data = await res.json();
                    const shortUrl = data.shortUrl || data.url || data.short_url || (typeof data === 'string' ? data : '');
                    
                    if (!shortUrl) {
                        throw new Error('Invalid response from server');
                    }

                    shortUrlLink.href = shortUrl;
                    shortUrlLink.textContent = shortUrl;
                    resultBox.style.display = 'block';
                } catch (err) {
                    errorMessage.textContent = err.message || 'An unexpected error occurred.';
                    errorBox.style.display = 'block';
                } finally {
                    submitBtn.disabled = false;
                    btnText.style.display = 'inline';
                    loadingSpinner.style.display = 'none';
                }
            });

            copyBtn.addEventListener('click', () => {
                const url = shortUrlLink.href;
                navigator.clipboard.writeText(url).then(() => {
                    copyText.textContent = 'Copied!';
                    setTimeout(() => { copyText.textContent = 'Copy'; }, 2000);
                }).catch(() => {
                    copyText.textContent = 'Failed';
                    setTimeout(() => { copyText.textContent = 'Copy'; }, 2000);
                });
            });
        });
    """.trimIndent()
}

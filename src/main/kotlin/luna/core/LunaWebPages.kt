package luna.core

object LunaWebPages {
    fun getPublicShortenPageHtml(): String =
        """
<!DOCTYPE html>
<html lang="zh-TW">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Luna Curtly - 高速短網址生成服務</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-dark: #0b0f19;
            --card-bg: rgba(23, 32, 54, 0.75);
            --border-color: rgba(255, 255, 255, 0.08);
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --primary-glow: rgba(99, 102, 241, 0.35);
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --accent-purple: #a855f7;
            --danger: #ef4444;
            --success: #10b981;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif;
        }

        body {
            background-color: var(--bg-dark);
            background-image: 
                radial-gradient(at 10% 10%, rgba(99, 102, 241, 0.12) 0px, transparent 40%),
                radial-gradient(at 90% 90%, rgba(168, 85, 247, 0.12) 0px, transparent 40%);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }

        /* Navbar */
        .navbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 1.25rem 2rem;
            background: rgba(15, 23, 42, 0.8);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border-color);
        }

        .nav-brand {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            font-size: 1.35rem;
            font-weight: 700;
            background: linear-gradient(135deg, #a855f7, #6366f1);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            text-decoration: none;
        }

        .btn {
            padding: 0.55rem 1.1rem;
            border-radius: 10px;
            border: none;
            font-weight: 600;
            font-size: 0.9rem;
            cursor: pointer;
            text-decoration: none;
            transition: all 0.2s ease;
        }

        .btn-primary {
            background: linear-gradient(135deg, #6366f1, #4f46e5);
            color: white;
            box-shadow: 0 4px 14px var(--primary-glow);
        }

        .btn-primary:hover {
            transform: translateY(-1px);
            box-shadow: 0 6px 18px var(--primary-glow);
        }

        /* Main Container */
        .main-container {
            flex: 1;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 2rem 1.5rem;
        }

        .shorten-card {
            width: 100%;
            max-width: 680px;
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 24px;
            padding: 2.5rem 2rem;
            backdrop-filter: blur(16px);
            box-shadow: 0 25px 50px rgba(0, 0, 0, 0.6);
            animation: fadeIn 0.4s ease-out;
        }

        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(12px); }
            to { opacity: 1; transform: translateY(0); }
        }

        .card-header {
            text-align: center;
            margin-bottom: 2rem;
        }

        .card-title {
            font-size: 1.85rem;
            font-weight: 700;
            background: linear-gradient(135deg, #a855f7, #6366f1);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 0.4rem;
        }

        .card-subtitle {
            font-size: 0.95rem;
            color: var(--text-muted);
        }

        .form-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 1rem;
            margin-bottom: 1.5rem;
        }

        @media (max-width: 600px) {
            .form-grid {
                grid-template-columns: 1fr;
            }
        }

        .form-group {
            display: flex;
            flex-direction: column;
            gap: 0.4rem;
        }

        .form-group.full-width {
            grid-column: 1 / -1;
        }

        .form-label {
            font-size: 0.85rem;
            font-weight: 500;
            color: var(--text-muted);
        }

        .input-field {
            width: 100%;
            padding: 0.8rem 1rem;
            background: rgba(15, 23, 42, 0.7);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            color: var(--text-main);
            font-size: 0.95rem;
            outline: none;
            transition: border-color 0.2s ease, box-shadow 0.2s ease;
        }

        .input-field:focus {
            border-color: var(--primary);
            box-shadow: 0 0 0 3px var(--primary-glow);
        }

        .submit-btn {
            width: 100%;
            padding: 0.95rem;
            background: linear-gradient(135deg, #6366f1, #4f46e5);
            color: white;
            font-weight: 600;
            font-size: 1.05rem;
            border: none;
            border-radius: 12px;
            cursor: pointer;
            transition: all 0.2s ease;
            box-shadow: 0 4px 15px var(--primary-glow);
        }

        .submit-btn:hover {
            transform: translateY(-1px);
            box-shadow: 0 6px 20px var(--primary-glow);
        }

        /* Result Section */
        .result-box {
            margin-top: 1.75rem;
            padding: 1.5rem;
            background: rgba(15, 23, 42, 0.8);
            border: 1px solid rgba(99, 102, 241, 0.3);
            border-radius: 16px;
            display: none;
            animation: fadeIn 0.3s ease-out;
        }

        .result-header {
            font-size: 0.9rem;
            color: var(--success);
            font-weight: 600;
            margin-bottom: 0.75rem;
            display: flex;
            align-items: center;
            gap: 0.4rem;
        }

        .result-url-wrapper {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            background: rgba(0, 0, 0, 0.25);
            padding: 0.75rem 1rem;
            border-radius: 10px;
            border: 1px solid var(--border-color);
        }

        .result-url-text {
            flex: 1;
            font-family: monospace;
            font-size: 1.1rem;
            font-weight: 600;
            color: #a5b4fc;
            word-break: break-all;
        }

        .btn-copy {
            background: var(--primary);
            color: white;
            border: none;
            padding: 0.5rem 1rem;
            border-radius: 8px;
            font-size: 0.85rem;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
            white-space: nowrap;
        }

        .btn-copy:hover {
            background: var(--primary-hover);
        }

        .footer-note {
            text-align: center;
            margin-top: 2rem;
            font-size: 0.9rem;
            color: var(--text-muted);
        }

        .footer-note a {
            color: var(--primary);
            text-decoration: none;
            font-weight: 600;
        }

        .footer-note a:hover {
            text-decoration: underline;
        }
    </style>
</head>
<body>
    <nav class="navbar">
        <a href="/shorten" class="nav-brand">
            <span>🌙</span>
            <span>Luna Curtly</span>
        </a>
        <div>
            <a href="/dashboard" class="btn btn-primary">控制台 Dashboard</a>
        </div>
    </nav>

    <div class="main-container">
        <div class="shorten-card">
            <div class="card-header">
                <h1 class="card-title">✨ 建立新短網址</h1>
                <p class="card-subtitle">快速生成簡短、優雅且高效的短鏈接</p>
            </div>

            <form onsubmit="handleShorten(event)">
                <div class="form-grid">
                    <div class="form-group full-width">
                        <label class="form-label" for="longUrl">長網址 Destination URL *</label>
                        <input type="url" id="longUrl" class="input-field" placeholder="https://example.com/very-long-url" required>
                    </div>
                    <div class="form-group full-width">
                        <label class="form-label" for="customKey">自訂別名 Custom Key (選填)</label>
                        <input type="text" id="customKey" class="input-field" placeholder="未填寫則隨機生成">
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="expiresInSeconds">有效秒數 Expires (選填)</label>
                        <input type="number" id="expiresInSeconds" class="input-field" placeholder="例如 86400 (一天)">
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="maxClicks">點擊上限 Max Clicks (選填)</label>
                        <input type="number" id="maxClicks" class="input-field" placeholder="例如 100">
                    </div>
                </div>

                <button type="submit" id="submit-btn" class="submit-btn">🚀 生成短網址</button>
            </form>

            <div id="result-box" class="result-box">
                <div class="result-header">🎉 短網址生成成功！</div>
                <div class="result-url-wrapper">
                    <div id="result-url" class="result-url-text">https://...</div>
                    <button class="btn-copy" onclick="copyResultUrl()">📋 複製</button>
                </div>
            </div>

            <div class="footer-note">
                想要追蹤詳細點擊流量與管理歷史鏈接？<a href="/login">使用 Discord 登入控制台</a>
            </div>
        </div>
    </div>

    <script>
        let generatedShortUrl = '';

        async function handleShorten(event) {
            event.preventDefault();
            const longUrl = document.getElementById('longUrl').value.trim();
            const customKey = document.getElementById('customKey').value.trim();
            const expiresInSeconds = parseInt(document.getElementById('expiresInSeconds').value) || null;
            const maxClicks = parseInt(document.getElementById('maxClicks').value) || null;

            const btn = document.getElementById('submit-btn');
            btn.disabled = true;
            btn.innerText = '生成中...';

            const payload = { longUrl };
            if (customKey) payload.customKey = customKey;
            if (expiresInSeconds) payload.expiresInSeconds = expiresInSeconds;
            if (maxClicks) payload.maxClicks = maxClicks;

            try {
                const res = await fetch('/api/shorten', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();

                if (!res.ok) throw new Error(data.error || '生成失敗');

                generatedShortUrl = data.shortUrl;
                document.getElementById('result-url').innerText = generatedShortUrl;
                document.getElementById('result-box').style.display = 'block';
            } catch (err) {
                alert('❌ 失敗: ' + err.message);
            } finally {
                btn.disabled = false;
                btn.innerText = '🚀 立即生成短網址';
            }
        }

        function copyResultUrl() {
            if (!generatedShortUrl) return;
            navigator.clipboard.writeText(generatedShortUrl).then(() => {
                alert('已複製短網址至剪貼簿：\n' + generatedShortUrl);
            });
        }
    </script>
</body>
</html>
        """.trimIndent()

    fun getLoginPageHtml(): String =
        """
<!DOCTYPE html>
<html lang="zh-TW">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Luna 登入 - 短網址管理系統</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-dark: #0f172a;
            --card-bg: rgba(30, 41, 59, 0.7);
            --border-color: rgba(255, 255, 255, 0.1);
            --primary: #6366f1;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif;
        }

        body {
            background-color: var(--bg-dark);
            background-image: 
                radial-gradient(at 0% 0%, rgba(99, 102, 241, 0.15) 0px, transparent 50%),
                radial-gradient(at 100% 100%, rgba(168, 85, 247, 0.15) 0px, transparent 50%);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1.5rem;
        }

        .auth-container {
            width: 100%;
            max-width: 420px;
            background: var(--card-bg);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border: 1px solid var(--border-color);
            border-radius: 24px;
            padding: 2.5rem 2rem;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5);
            text-align: center;
            animation: fadeIn 0.4s ease-out;
        }

        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(12px); }
            to { opacity: 1; transform: translateY(0); }
        }

        .brand-logo {
            font-size: 3rem;
            margin-bottom: 0.5rem;
        }

        .brand-title {
            font-size: 1.85rem;
            font-weight: 700;
            background: linear-gradient(135deg, #a855f7, #6366f1);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }

        .brand-subtitle {
            font-size: 0.9rem;
            color: var(--text-muted);
            margin-top: 0.25rem;
            margin-bottom: 2rem;
        }

        .discord-btn {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            padding: 0.95rem 1.25rem;
            background: #5865F2;
            color: #ffffff;
            font-weight: 600;
            font-size: 1.05rem;
            border-radius: 14px;
            text-decoration: none;
            transition: all 0.2s ease;
            box-shadow: 0 6px 20px rgba(88, 101, 242, 0.4);
        }

        .discord-btn:hover {
            background: #4752C4;
            transform: translateY(-2px);
            box-shadow: 0 8px 25px rgba(88, 101, 242, 0.55);
        }

        .public-link {
            margin-top: 2rem;
            font-size: 0.875rem;
            color: var(--text-muted);
        }

        .public-link a {
            color: var(--primary);
            text-decoration: none;
            font-weight: 500;
        }

        .public-link a:hover {
            text-decoration: underline;
        }
    </style>
</head>
<body>
    <div class="auth-container">
        <div class="brand-logo">🌙</div>
        <h1 class="brand-title">Luna Curtly</h1>
        <p class="brand-subtitle">登入以管理並分析您的個人短網址鏈接</p>

        <a href="/api/auth/discord/login" class="discord-btn">
            <svg width="22" height="22" viewBox="0 0 127.14 96.36" fill="currentColor" style="margin-right: 10px;">
                <path d="M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0,0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71,56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42,68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a74.4,74.4,0,0,0,64.3,0c.87.69,1.76,1.37,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1,105.25,105.25,0,0,0,32.19-16.14c2.64-27.38-4.51-51.11-18.93-72.15ZM42.45,65.69C36.18,65.69,31,60,31,53s5-12.74,11.43-12.74S54,45.91,53.87,53,48.73,65.69,42.45,65.69Zm42.24,0C78.41,65.69,73.25,60,73.25,53s5-12.74,11.44-12.74S96.23,45.91,96.1,53,91,65.69,84.69,65.69Z"/>
            </svg>
            使用 Discord 帳號一鍵登入
        </a>

        <div class="public-link">
            不需要帳號？<a href="/shorten">直接使用公開縮網址</a>
        </div>
    </div>
</body>
</html>
        """.trimIndent()

    fun getDashboardPageHtml(username: String): String =
        """
<!DOCTYPE html>
<html lang="zh-TW">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Luna Curtly - 短鏈接管理與分析控制台</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-dark: #0b0f19;
            --card-bg: rgba(23, 32, 54, 0.75);
            --border-color: rgba(255, 255, 255, 0.08);
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --primary-glow: rgba(99, 102, 241, 0.35);
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --accent-purple: #a855f7;
            --danger: #ef4444;
            --success: #10b981;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif;
        }

        body {
            background-color: var(--bg-dark);
            background-image: 
                radial-gradient(at 10% 10%, rgba(99, 102, 241, 0.12) 0px, transparent 40%),
                radial-gradient(at 90% 90%, rgba(168, 85, 247, 0.12) 0px, transparent 40%);
            color: var(--text-main);
            min-height: 100vh;
            padding-bottom: 3rem;
        }

        /* Navbar */
        .navbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 1.25rem 2rem;
            background: rgba(15, 23, 42, 0.8);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border-color);
            position: sticky;
            top: 0;
            z-index: 100;
        }

        .nav-brand {
            display: flex;
            align-items: center;
            gap: 0.75rem;
            font-size: 1.35rem;
            font-weight: 700;
            background: linear-gradient(135deg, #a855f7, #6366f1);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            text-decoration: none;
        }

        .user-nav {
            display: flex;
            align-items: center;
            gap: 1rem;
        }

        .user-badge {
            background: rgba(99, 102, 241, 0.15);
            border: 1px solid rgba(99, 102, 241, 0.3);
            color: #a5b4fc;
            padding: 0.4rem 0.9rem;
            border-radius: 20px;
            font-size: 0.875rem;
            font-weight: 500;
        }

        .btn {
            padding: 0.55rem 1.1rem;
            border-radius: 10px;
            border: none;
            font-weight: 600;
            font-size: 0.9rem;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .btn-logout {
            background: rgba(239, 68, 68, 0.15);
            color: #fca5a5;
            border: 1px solid rgba(239, 68, 68, 0.3);
        }

        .btn-logout:hover {
            background: rgba(239, 68, 68, 0.25);
        }

        .btn-primary {
            background: linear-gradient(135deg, #6366f1, #4f46e5);
            color: white;
            box-shadow: 0 4px 14px var(--primary-glow);
        }

        .btn-primary:hover {
            transform: translateY(-1px);
            box-shadow: 0 6px 18px var(--primary-glow);
        }

        /* Container */
        .container {
            max-width: 1140px;
            margin: 2rem auto;
            padding: 0 1.5rem;
        }

        /* Summary Stats Cards */
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
            gap: 1.25rem;
            margin-bottom: 2rem;
        }

        .stat-card {
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 16px;
            padding: 1.5rem;
            backdrop-filter: blur(12px);
        }

        .stat-label {
            font-size: 0.85rem;
            color: var(--text-muted);
            margin-bottom: 0.5rem;
        }

        .stat-value {
            font-size: 2rem;
            font-weight: 700;
            color: var(--text-main);
        }

        /* Create Link Form Section */
        .card-section {
            background: var(--card-bg);
            border: 1px solid var(--border-color);
            border-radius: 20px;
            padding: 1.75rem;
            backdrop-filter: blur(12px);
            margin-bottom: 2rem;
        }

        .section-title {
            font-size: 1.2rem;
            font-weight: 600;
            margin-bottom: 1.25rem;
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }

        .create-form-grid {
            display: grid;
            grid-template-columns: 2.2fr 1.6fr 1fr 1fr;
            gap: 1.25rem;
        }

        @media (max-width: 992px) {
            .create-form-grid {
                grid-template-columns: 1fr 1fr;
            }
        }

        @media (max-width: 600px) {
            .create-form-grid {
                grid-template-columns: 1fr;
            }
        }

        .form-input-box {
            display: flex;
            flex-direction: column;
            gap: 0.4rem;
        }

        .form-input-box label {
            font-size: 0.82rem;
            color: var(--text-muted);
            white-space: nowrap;
        }

        .input-field {
            padding: 0.7rem 0.9rem;
            background: rgba(15, 23, 42, 0.7);
            border: 1px solid var(--border-color);
            border-radius: 10px;
            color: var(--text-main);
            font-size: 0.95rem;
            outline: none;
        }

        .input-field:focus {
            border-color: var(--primary);
        }

        /* Table */
        .table-responsive {
            width: 100%;
            overflow-x: auto;
        }

        .data-table {
            width: 100%;
            border-collapse: collapse;
            text-align: left;
        }

        .data-table th {
            padding: 0.9rem 1rem;
            font-size: 0.85rem;
            color: var(--text-muted);
            border-bottom: 1px solid var(--border-color);
            font-weight: 600;
        }

        .data-table td {
            padding: 1rem;
            border-bottom: 1px solid var(--border-color);
            font-size: 0.9rem;
        }

        .data-table tr:hover {
            background: rgba(255, 255, 255, 0.02);
        }

        .key-badge {
            font-weight: 600;
            color: #818cf8;
            background: rgba(99, 102, 241, 0.12);
            padding: 0.25rem 0.6rem;
            border-radius: 6px;
            font-family: monospace;
        }

        .long-url-text {
            max-width: 260px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            color: var(--text-muted);
        }

        .status-tag {
            padding: 0.25rem 0.6rem;
            border-radius: 12px;
            font-size: 0.75rem;
            font-weight: 600;
        }

        .status-tag.active {
            background: rgba(16, 185, 129, 0.15);
            color: #34d399;
        }

        .status-tag.expired {
            background: rgba(239, 68, 68, 0.15);
            color: #f87171;
        }

        .action-btns {
            display: flex;
            gap: 0.5rem;
        }

        .btn-sm {
            padding: 0.4rem 0.75rem;
            font-size: 0.8rem;
            border-radius: 8px;
        }

        .btn-info {
            background: rgba(168, 85, 247, 0.15);
            color: #c084fc;
            border: 1px solid rgba(168, 85, 247, 0.3);
        }

        .btn-info:hover {
            background: rgba(168, 85, 247, 0.25);
        }

        .btn-del {
            background: rgba(239, 68, 68, 0.12);
            color: #fca5a5;
            border: 1px solid rgba(239, 68, 68, 0.25);
        }

        .btn-del:hover {
            background: rgba(239, 68, 68, 0.25);
        }

        /* Analytics Modal */
        .modal-backdrop {
            position: fixed;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(8px);
            display: none;
            align-items: center;
            justify-content: center;
            z-index: 1000;
        }

        .modal-card {
            background: #131b2e;
            border: 1px solid var(--border-color);
            width: 90%;
            max-width: 800px;
            max-height: 85vh;
            border-radius: 20px;
            padding: 1.75rem;
            overflow-y: auto;
            box-shadow: 0 25px 50px rgba(0, 0, 0, 0.7);
        }

        .modal-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1.5rem;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 1rem;
        }

        .modal-close {
            background: transparent;
            border: none;
            color: var(--text-muted);
            font-size: 1.5rem;
            cursor: pointer;
        }

        .analytics-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 1.25rem;
            margin-bottom: 1.5rem;
        }

        @media (max-width: 640px) {
            .analytics-grid {
                grid-template-columns: 1fr;
            }
        }

        .analytics-box {
            background: rgba(15, 23, 42, 0.6);
            border: 1px solid var(--border-color);
            border-radius: 12px;
            padding: 1rem;
        }

        .analytics-box h4 {
            font-size: 0.9rem;
            color: #a5b4fc;
            margin-bottom: 0.75rem;
        }

        .stat-list {
            list-style: none;
        }

        .stat-list-item {
            display: flex;
            justify-content: space-between;
            font-size: 0.85rem;
            padding: 0.35rem 0;
            border-bottom: 1px solid rgba(255, 255, 255, 0.04);
        }

        .stat-list-item span:first-child {
            color: var(--text-muted);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            max-width: 240px;
        }

        .stat-list-item span:last-child {
            font-weight: 600;
            color: var(--text-main);
        }
    </style>
</head>
<body>
    <nav class="navbar">
        <a href="/dashboard" class="nav-brand">
            <span>🌙</span>
            <span>Luna Curtly Dashboard</span>
        </a>
        <div class="user-nav">
            <span class="user-badge">👤 $username</span>
            <button class="btn btn-logout" onclick="handleLogout()">登出</button>
        </div>
    </nav>

    <div class="container">
        <!-- Quick Stats -->
        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-label">我的短鏈接總數</div>
                <div class="stat-value" id="stat-total-links">0</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">總累計點擊次數</div>
                <div class="stat-value" id="stat-total-clicks">0</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">活躍中的短鏈接</div>
                <div class="stat-value" id="stat-active-links">0</div>
            </div>
        </div>

        <!-- Create Link Form -->
        <div class="card-section">
            <h2 class="section-title">✨ 建立新短網址</h2>
            <form onsubmit="handleCreateLink(event)" class="create-form-grid">
                <div class="form-input-box">
                    <label>長網址 Destination URL *</label>
                    <input type="url" id="new-long-url" class="input-field" placeholder="https://example.com/very-long-url" required>
                </div>
                <div class="form-input-box">
                    <label>自訂別名 Custom Key (選填)</label>
                    <input type="text" id="new-custom-key" class="input-field" placeholder="未填寫則隨機生成">
                </div>
                <div class="form-input-box">
                    <label>有效秒數 Expires (選填)</label>
                    <input type="number" id="new-expires" class="input-field" placeholder="例如 86400 (一天)">
                </div>
                <div class="form-input-box">
                    <label>點擊上限 Max Clicks (選填)</label>
                    <input type="number" id="new-max-clicks" class="input-field" placeholder="例如 100">
                </div>
                <div style="grid-column: 1 / -1; margin-top: 0.5rem; text-align: right;">
                    <button type="submit" class="btn btn-primary">🚀 生成短網址</button>
                </div>
            </form>
        </div>

        <!-- Short Links Table -->
        <div class="card-section">
            <h2 class="section-title">🔗 我的短鏈接列表</h2>
            <div class="table-responsive">
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>Key 別名</th>
                            <th>短網址 Short URL</th>
                            <th>原始長網址 Long URL</th>
                            <th>點擊次數</th>
                            <th>狀態</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody id="links-table-body">
                        <tr>
                            <td colspan="6" style="text-align: center; color: var(--text-muted); padding: 2rem;">載入中...</td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- Analytics Modal -->
    <div class="modal-backdrop" id="analytics-modal">
        <div class="modal-card">
            <div class="modal-header">
                <h3 id="modal-title">短網址詳細點擊分析 - key</h3>
                <button class="modal-close" onclick="closeAnalyticsModal()">&times;</button>
            </div>
            <div id="modal-content">
                <!-- Dynamic analytics data -->
            </div>
        </div>
    </div>

    <script>
        let currentUrls = [];

        document.addEventListener('DOMContentLoaded', () => {
            loadUserUrls();
        });

        async function loadUserUrls() {
            try {
                const res = await fetch('/api/user/urls');
                if (res.status === 401) {
                    window.location.href = '/login';
                    return;
                }
                if (!res.ok) {
                    const errData = await res.json().catch(() => ({}));
                    throw new Error(errData.error || '無法載入短網址資料');
                }
                const data = await res.json();
                currentUrls = Array.isArray(data) ? data : [];
                renderUrlsTable(currentUrls);
                updateSummaryStats(currentUrls);
            } catch (err) {
                console.error(err);
                const tbody = document.getElementById('links-table-body');
                if (tbody) {
                    tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: #f87171; padding: 2rem;">❌ 載入失敗：${'$'}{err.message}</td></tr>`;
                }
            }
        }

        function updateSummaryStats(urls) {
            document.getElementById('stat-total-links').innerText = urls.length;
            const totalClicks = urls.reduce((sum, u) => sum + (u.clickCount || 0), 0);
            document.getElementById('stat-total-clicks').innerText = totalClicks;
            
            const now = Date.now();
            const active = urls.filter(u => {
                if (u.expiresAt && u.expiresAt < now) return false;
                if (u.maxClicks && u.clickCount >= u.maxClicks) return false;
                return true;
            }).length;
            document.getElementById('stat-active-links').innerText = active;
        }

        function renderUrlsTable(urls) {
            const tbody = document.getElementById('links-table-body');
            if (!urls || urls.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-muted); padding: 2rem;">💡 尚未建立任何短網址！在上方的表單建立一個吧。</td></tr>';
                return;
            }

            const now = Date.now();
            tbody.innerHTML = urls.map(u => {
                const isExpired = (u.expiresAt && u.expiresAt < now) || (u.maxClicks && u.clickCount >= u.maxClicks);
                const statusHtml = isExpired ? '<span class="status-tag expired">已失效</span>' : '<span class="status-tag active">正常</span>';
                
                return `
                    <tr>
                        <td><span class="key-badge">${'$'}{u.key}</span></td>
                        <td>
                            <a href="${'$'}{u.shortUrl}" target="_blank" style="color: #a5b4fc; text-decoration: none;">${'$'}{u.shortUrl}</a>
                            <button class="btn btn-sm" onclick="copyToClipboard('${'$'}{u.shortUrl}')" style="margin-left: 0.4rem; padding: 0.2rem 0.5rem;">複製</button>
                        </td>
                        <td><div class="long-url-text" title="${'$'}{u.longUrl}">${'$'}{u.longUrl}</div></td>
                        <td><strong>${'$'}{u.clickCount || 0}</strong> ${'$'}{u.maxClicks ? '/ ' + u.maxClicks : ''}</td>
                        <td>${'$'}{statusHtml}</td>
                        <td>
                            <div class="action-btns">
                                <button class="btn btn-sm btn-info" onclick="viewAnalytics('${'$'}{u.key}')">📊 分析細節</button>
                                <button class="btn btn-sm btn-del" onclick="deleteUrl('${'$'}{u.key}')">🗑️ 刪除</button>
                            </div>
                        </td>
                    </tr>
                `;
            }).join('');
        }

        async function handleCreateLink(event) {
            event.preventDefault();
            const longUrl = document.getElementById('new-long-url').value.trim();
            const customKey = document.getElementById('new-custom-key').value.trim();
            const expiresInSeconds = parseInt(document.getElementById('new-expires').value) || null;
            const maxClicks = parseInt(document.getElementById('new-max-clicks').value) || null;

            const payload = { longUrl };
            if (customKey) payload.customKey = customKey;
            if (expiresInSeconds) payload.expiresInSeconds = expiresInSeconds;
            if (maxClicks) payload.maxClicks = maxClicks;

            try {
                const res = await fetch('/api/shorten', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();
                if (!res.ok) throw new Error(data.error || '生成短網址失敗');

                // Clear fields & reload
                document.getElementById('new-long-url').value = '';
                document.getElementById('new-custom-key').value = '';
                document.getElementById('new-expires').value = '';
                document.getElementById('new-max-clicks').value = '';

                alert('🎉 短網址建立成功！\n' + data.shortUrl);
                loadUserUrls();
            } catch (err) {
                alert('❌ 失敗: ' + err.message);
            }
        }

        async function deleteUrl(key) {
            if (!confirm(`確定要刪除短網址 [${'$'}{key}] 嗎？`)) return;
            try {
                const res = await fetch(`/api/user/urls/${'$'}{key}`, { method: 'DELETE' });
                if (!res.ok) throw new Error('刪除失敗');
                loadUserUrls();
            } catch (err) {
                alert(err.message);
            }
        }

        async function viewAnalytics(key) {
            document.getElementById('modal-title').innerText = `短網址 [ ${'$'}{key} ] 詳細點擊分析`;
            document.getElementById('modal-content').innerHTML = '<p style="text-align:center; padding: 2rem; color: var(--text-muted);">正在載入數據點擊日誌...</p>';
            document.getElementById('analytics-modal').style.display = 'flex';

            try {
                const res = await fetch(`/api/user/urls/${'$'}{key}/stats`);
                if (!res.ok) throw new Error('無法載入分析數據');
                const stats = await res.json();

                renderAnalyticsContent(stats);
            } catch (err) {
                document.getElementById('modal-content').innerHTML = `<p style="color: var(--danger);">${'$'}{err.message}</p>`;
            }
        }

        function renderAnalyticsContent(stats) {
            const container = document.getElementById('modal-content');
            
            const formatMap = (map) => {
                if (!map || Object.keys(map).length === 0) return '<div class="stat-list-item"><span>無資料</span><span>0</span></div>';
                return Object.entries(map).map(([k, v]) => `
                    <div class="stat-list-item">
                        <span title="${'$'}{k}">${'$'}{k}</span>
                        <span>${'$'}{v} 次</span>
                    </div>
                `).join('');
            };

            const logsHtml = (stats.logs || []).slice(0, 15).map(log => `
                <div class="stat-list-item">
                    <span>${'$'}{new Date(log.timestamp).toLocaleString()} | IP: ${'$'}{log.ip || 'Unknown'} | Referer: ${'$'}{log.referer || 'Direct'}</span>
                    <span>${'$'}{log.userAgent || 'Unknown'}</span>
                </div>
            `).join('');

            container.innerHTML = `
                <div class="analytics-grid">
                    <div class="analytics-box">
                        <h4>📅 每日點擊數量 (Clicks by Date)</h4>
                        <div class="stat-list">${'$'}{formatMap(stats.clicksByDate)}</div>
                    </div>
                    <div class="analytics-box">
                        <h4>🌐 來源網站 (Top Referrers)</h4>
                        <div class="stat-list">${'$'}{formatMap(stats.topReferers)}</div>
                    </div>
                    <div class="analytics-box">
                        <h4>💻 裝置 / 瀏覽器 (Top User-Agents)</h4>
                        <div class="stat-list">${'$'}{formatMap(stats.topUserAgents)}</div>
                    </div>
                    <div class="analytics-box">
                        <h4>📍 點擊 IP 來源 (Top IPs)</h4>
                        <div class="stat-list">${'$'}{formatMap(stats.topIps)}</div>
                    </div>
                </div>

                <div class="analytics-box" style="margin-top: 1rem;">
                    <h4>📜 近期點擊詳細紀錄 (Recent Click Logs)</h4>
                    <div class="stat-list">
                        ${'$'}{logsHtml || '<div class="stat-list-item"><span>暫無點擊紀錄</span></div>'}
                    </div>
                </div>
            `;
        }

        function closeAnalyticsModal() {
            document.getElementById('analytics-modal').style.display = 'none';
        }

        function handleLogout() {
            fetch('/api/auth/logout', { method: 'POST' }).then(() => {
                window.location.href = '/login';
            });
        }

        function copyToClipboard(text) {
            navigator.clipboard.writeText(text).then(() => {
                alert('已複製短網址至剪貼簿：\n' + text);
            });
        }
    </script>
</body>
</html>
        """.trimIndent()
}

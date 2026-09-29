# NexusHome Static Website

Official static landing page and APK distribution portal for **NexusHome**.

## 🚀 How to Deploy on Vercel

### Option 1: Via Vercel Dashboard (Easiest)
1. Go to [vercel.com](https://vercel.com) and log in with your GitHub account.
2. Click **Add New...** → **Project**.
3. Select the **`NexusHome`** repository.
4. In the project configuration:
   - **Framework Preset**: *Other*
   - **Root Directory**: Click *Edit* and select **`website`**
5. Click **Deploy**!
   Your site will be live instantly with a free `*.vercel.app` URL (e.g., `https://nexushome.vercel.app`).

---

### Option 2: Via Vercel CLI
If you have the Vercel CLI installed:
```bash
cd website
vercel
```
Follow the terminal prompts to deploy.

---

## 📱 Features of the Static Site
- **Live Interactive Phone Mockup**: Visitors can click to toggle lights, spin the fan, trigger the calibrated 25s motorized curtain progress bar, and test auto-mode temperature switching.
- **Dynamic APK Download Button**: Automatically pulls the latest release tag (`v1.0.4`) and links directly to the production signed APK on GitHub.
- **Pure Vanilla Stack**: Built with semantic HTML5, modern vanilla CSS (glassmorphism & cyber-clean dark mode), and lightweight JavaScript. Zero external frameworks, ensuring ultra-fast load times.

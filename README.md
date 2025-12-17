# CosmeticsLite (Forge 1.20.1 • Java 17)

A lightweight, Forge-based cosmetics framework for Minecraft **1.20.1** — hats, capes, pets, particles, and gadgets — with a clean preview system and simple server-side entitlements.

> **Project goals:** provide a solid, readable foundation other devs can extend for Forge 1.20.1 where few modern cosmetics frameworks exist.

---

## ✨ Features

- **Cosmetic categories:** Hats, Capes, Pets, Particles, Gadgets  
- **Client previews:** unified mannequin + quickmenu with safe open/close guards  
- **Network sync:** compact packets for equip/unequip, color/variant, and entitlements  
- **Entitlements:** persistent player grants (packs or specific cosmetics) + admin bypass  
- **Data-driven hats:** JSON baked models under `assets/cosmeticslite/models/hats/**`  
- **Cape layer:** corrected orientation & motion, synced with player movement  
- **Mini-Games** Polished with SFX and LeaderBoard

---

## 📦 Requirements

- **Minecraft:** 1.20.1  
- **Forge:** 47.x (tested on 47.4.0)  
- **Java:** 17

---

## 🛠️ Build from source

Clone and build:

```bash
# Windows (PowerShell/Git Bash) or Linux/macOS
git clone https://github.com/PunJedi/CosmeticsLite.git
cd CosmeticsLite
./gradlew build

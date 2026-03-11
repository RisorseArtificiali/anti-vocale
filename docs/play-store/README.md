# Play Store Assets

This directory contains all assets needed for publishing Anti-Vocale to the Google Play Store.

## ✅ Ready Files

| File | Purpose | Status |
|------|---------|--------|
| `store-listing.md` | App title, description, category | Ready to copy |
| `../PRIVACY_POLICY.md` | Privacy policy content | Ready to host |
| `screenshots/` | 7 processed screenshots (1264x2635) | Ready to upload |

### Screenshots List
- `log_tab.png` - Transcription history view
- `model_tab_top.png` - Model selection (top section)
- `model_tab_bottom.png` - Model selection (download section)
- `settings_tab_top.png` - Settings (top section)
- `settings_tab_mid.png` - Settings (middle section)
- `settings_tab_bottom.png` - Settings (bottom section)
- `notification.png` - Transcription result notification

## ⏳ To Be Created

| File | Required Size | Purpose |
|------|---------------|---------|
| `feature-graphic.png` | 1024x500 | Featured banner in Play Store |
| `store-icon-512.png` | 512x512 | High-res app icon |

## 📋 Quick Copy for Play Console

### Short Description (80 chars):
```
Transcribe voice messages offline. No internet required.
```

### App Title:
```
Anti-Vocale
```

### Full Description:
See `store-listing.md`

### Category:
Tools

### Content Rating:
Everyone (IARC questionnaire)

## 🔗 How to Use These Assets

### 1. Feature Graphic
See `feature-graphic-design-brief.md` for design requirements.

### 2. Store Icon
Export your app launcher icon at 512x512:
```bash
# From app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
# Scale up to 512x512 using ImageMagick:
convert app/src/main/res/mipmap-xxxhdpi/ic_launcher.png \
  -resize 512x512 store-icon-512.png
```

### 3. Screenshots
All screenshots in `screenshots/` are ready to upload directly to Play Console.

### 4. Privacy Policy
1. Copy `../PRIVACY_POLICY.md` content
2. Host at a public URL (GitHub Pages recommended)
3. Add URL to Play Console listing

## 📊 Asset Summary

```
play-store/
├── feature-graphic.png         ⏳ CREATE (1024x500)
├── store-icon-512.png          ⏳ CREATE (512x512)
├── store-listing.md            ✅ READY
├── feature-graphic-design-brief.md  📖 Design guide
├── screenshot-processing-guide.md  📖 Processing guide
└── screenshots/
    ├── log_tab.png             ✅ 1264x2635
    ├── model_tab_top.png       ✅ 1264x2635
    ├── model_tab_bottom.png    ✅ 1264x2635
    ├── settings_tab_top.png    ✅ 1264x2635
    ├── settings_tab_mid.png    ✅ 1264x2635
    ├── settings_tab_bottom.png ✅ 1264x2635
    └── notification.png        ✅ 1264x2635
```

## 🎨 Design Colors Reference

The app uses Material 3 colors. Check `app/src/main/res/values/colors.xml`:
- Primary: Typically indigo/purple tones
- Background: Dark/light theme support
- Accent: Used for buttons and highlights

Use these colors when creating the feature graphic for consistency.

## 📝 Notes

- All screenshots have been cropped to remove status bar and navigation bar
- Screenshots are in English locale
- Aspect ratio is approximately 9:16 (portrait)
- All dimensions meet Play Store requirements (min 320px, max 3840px)

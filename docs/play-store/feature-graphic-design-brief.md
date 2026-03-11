# Feature Graphic Design Brief

**Required Size:** 1024x500 pixels (PNG or JPG)
**Purpose:** Play Store featured banner that appears at the top of the store listing

## Design Requirements

### Content Elements

1. **App Name:** "Anti-Vocale" (prominent, top-left or center)
2. **Tagline:** "Offline Voice Transcription" (smaller, below app name)
3. **Visual:** Smartphone mockup showing transcription workflow

### Visual Layout

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  [Logo/Icon]  Anti-Vocale                          [Visual] │
│               Offline Voice Transcription      Smartphone   │
│                                                 Mockup      │
│                                                             │
│  📱 Share → 🔊 Transcribe → 📝 Text                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Color Scheme

Use the app's existing theme colors (check `app/src/main/res/values/colors.xml`):
- Primary color: Typically a blue/purple gradient
- Background: Dark or light (match app theme)
- Text: White (if dark background) or dark (if light background)

### Design Concept Options

#### Option A: Clean Minimalist
- Solid gradient background (app's primary color)
- White text "Anti-Vocale" large, left-aligned
- Smaller tagline below
- Simple phone outline on the right showing the transcription UI

#### Option B: Feature Showcase
- Split layout: text on left, visual on right
- Show 3 icons horizontally: Share icon → Microphone icon → Text icon
- Demonstrates the workflow visually
- Add "100% Offline" badge

#### Option C: Dark Theme Modern
- Dark background with gradient accent
- Glowing/embossed app name
- Phone mockup showing actual app UI
- Privacy badge: "🔒 Your data stays on your device"

### Text Specifications

- **App Name:** Bold, 60-80px
- **Tagline:** Regular/Medium, 30-40px
- **Workflow icons:** 80-100px each
- **Badges:** Small, 20-24px text

### Smartphone Mockup Content

The phone screen should show:
- A voice message waveform or audio icon
- An arrow or swipe animation
- Transcribed text appearing
- Simple and recognizable at small sizes

### File Export

- **Format:** PNG (for transparency) or high-quality JPG
- **Resolution:** 1024x500 pixels exactly
- **Color Profile:** sRGB
- **Compression:** PNG-24 or JPG 90% quality

### Accessibility

- Ensure sufficient color contrast (WCAG AA minimum)
- Avoid relying solely on color to convey information
- Keep text readable at small sizes

### Tools to Create

1. **Figma/Canva:** Easiest for non-designers, has templates
2. **Photoshop/GIMP:** Full control over design
3. **Play Store Asset Generator:** Browser-based tool
4. **Android Studio:** Built-in Image Asset Studio (limited for banners)

## Quick Start Template (Canva/Figma)

1. Create new design: 1024x500px
2. Background: Linear gradient (top-left to bottom-right)
   - Start: #6366F1 (Indigo 500)
   - End: #8B5CF6 (Violet 500)
3. Add text:
   - "Anti-Vocale" - Bold, 72px, White, top-left (x:60, y:100)
   - "Offline Voice Transcription" - Regular, 36px, White, x:60, y:200
4. Add phone mockup on right side (x:650, y:50, size: ~350px tall)
5. Add workflow icons at bottom (y:380, evenly spaced)
6. Export as PNG at 1024x500px

## Approval Checklist

- [ ] Exactly 1024x500 pixels
- [ ] App name is clearly readable
- [ ] Tagline communicates the main benefit
- [ ] Visual supports the message (not decorative only)
- [ ] Colors match app branding
- [ ] No pixelation or artifacts when zoomed in
- [ ] Text is readable at 50% zoom
- [ ] File size under 1MB (recommended)

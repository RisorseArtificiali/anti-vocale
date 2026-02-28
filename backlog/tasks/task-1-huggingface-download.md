---
id: task-1
title: Add HuggingFace Model Download with Authentication
status: To Do
assignee: []
created_date: '2026-02-28'
labels: [enhancement, model-management, authentication]
dependencies: []
---

# Add HuggingFace Model Download with Authentication

## Description

Implement direct model download from HuggingFace within the app, similar to how Google AI Edge Gallery handles it. This eliminates the need for users to install Gallery or manually copy model files.

## User Story

As a user, I want to download Gemma 3n models directly in the app by providing my HuggingFace access token, so I don't need to install additional apps or manually copy files.

## Requirements

### 1. HuggingFace Token Management
- Add "Settings" section in ModelTab
- Add HuggingFace token input field
- Store token securely using EncryptedSharedPreferences
- Add link to HF token settings page (huggingface.co/settings/tokens)

### 2. Model Download UI
- Add "Download from HuggingFace" button
- Show model selection (E2B / E4B)
- Display download progress dialog with percentage
- Handle download completion → auto-select model

### 3. Authentication
- Modify ModelDownloader to add `Authorization: Bearer hf_xxx` header
- Handle 401/403 errors with user-friendly messages
- Validate token before attempting download

### 4. Error Handling
- Invalid token → Link to HF settings
- License not accepted → Link to model page on HF
- Network errors → Retry option
- Insufficient storage → Show required space

## Technical Details

### HuggingFace API
```
Model URL: https://huggingface.co/{repo}/resolve/main/{filename}
Auth Header: Authorization: Bearer hf_xxxxxxxxxxxxxxxxxxxx
```

### Model Repositories
| Model | Repository | Filename | Size |
|-------|-----------|----------|------|
| E2B | google/gemma-3n-E2B-it-litert-lm | gemma-3n-E2B-it-int4.litertlm | 3.6GB |
| E4B | google/gemma-3n-E4B-it-litert-lm | gemma-3n-E4B-it-int4.litertlm | 4.2GB |

### Code Changes Required
- `ModelDownloader.kt` - Add auth header, progress callback
- `ModelTab.kt` - Add settings section, download UI
- `ModelViewModel.kt` - Add token management, download state
- `PreferencesManager.kt` - Add encrypted token storage

## Prerequisites for Users
1. HuggingFace account
2. Access token from huggingface.co/settings/tokens
3. License acceptance on model page (one-time browser visit)

## Acceptance Criteria
- [ ] User can input and save HF token
- [ ] Download shows progress percentage
- [ ] Downloaded model appears in model list
- [ ] Auth errors show helpful messages
- [ ] Token stored securely (not in plain text)

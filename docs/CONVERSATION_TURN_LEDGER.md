# Conversation Turn Ledger — Personal Memory AI

## Recording rule

For every subsequent project-related user turn and assistant turn, record a durable entry here or update the persistent context files. Each entry must preserve the user requirement, observed evidence, decisions, implementation actions, unresolved issues, and the next gate. This ledger complements `PROJECT_CONVERSATION_CONTEXT.md`, `PROJECT_PLAN.md`, and `PROJECT_PROGRESS.md`.

## 2026-08-28 — Picker clarification + large-selection/provider hardening

### User turn

The user corrected an earlier misunderstanding: in the latest installed build, pressing `OPEN SYSTEM FILES / GALLERY` did **not** expose or open Gallery/Studio, so the user never tested the Gallery's 500-image limit. The 500+ tests were through `Files -> Screenshots`: about 500 worked, more than 500 worked, but selecting all roughly 6,000–7,000 images caused the application to exit immediately. Separately, the in-app media browser showed about 215,992 images and stayed stable when Select All was activated; the user cancelled the long operation. Device inspection showed about 107,994 images.

The user requested: add a real Gallery/Studio option plus other source options without removing the existing Files option; preserve scalable bulk selection; fix the large-selection crash; continue tracking all chat context in GitHub.

The user also supplied diagnostics showing an import run with `total=860`, `added=571`, `failed=289`, including ordinary JPEG-family and TIFF `com.android.externalstorage.documents` URIs, demonstrating a provider-access problem as well as format/decode failures.

### Assistant turn / engineering decisions

1. Confirmed the exact distinction between Gallery testing and Files testing.
2. Identified the 6,000–7,000 individual Files-selection risk as an ActivityResult/ClipData transport problem, not only an in-app URI-list problem. A folder/tree streaming route is required for scalable thousands-of-images selection.
3. Updated `BulkImagePickerActivity` to expose five acquisition paths:
   - Gallery/Studio via runtime-resolved `ACTION_PICK`, then `ACTION_GET_CONTENT` fallback;
   - modern Photo Picker via `PickMultipleVisualMedia`;
   - existing system Files multi-document route;
   - explicit folder/tree route for streamed thousands;
   - in-app MediaStore browser.
4. Changed MediaStore enumeration to use only the aggregate external collection, avoiding aggregate+individual-volume double enumeration.
5. Preserved logical Select All and streams MediaStore IDs directly into the private `.uris` queue instead of materializing the corpus.
6. Changed selected URI preparation to stream prepared URIs one-by-one rather than creating another prepared URI collection.
7. Hardened `ImageCorpusImportWorker` with a `ParcelFileDescriptor` fallback before the direct primary-storage fallback, while retaining per-item failure isolation.
8. Updated `PROJECT_CONVERSATION_CONTEXT.md`, `PROJECT_PROGRESS.md`, and `docs/ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md` with the exact test distinction and root-cause analysis.

### Commits

- picker implementation: `15fd568f7133ff3ecbe59e2c89b67fa9f0edff88`
- provider-read hardening: `4d84bdaf67f9645ba4277beb63349cb4e0ff9078`
- durable context: `3b6dbf8460c1593335389360507a0e274fc4d62c`
- progress documentation: `e5fe513b3bda9625c6e1af844330720604333d0e`
- device findings: `a8de39315d91cc9d2304cd7c39b69eeda4f99d20`

### CI gate

GitHub Actions automatically started Android Build run `33188447792` for the latest commit `a8de39315d91cc9d2304cd7c39b69eeda4f99d20`. At recording time the build job was still in progress; no success claim was made from that run yet.

### Remaining verification

The next device validation must explicitly test:

- `OPEN SYSTEM FILES / GALLERY` shows Gallery, Photo Picker, Files, Folder and in-app options;
- Gallery route actually opens the device's media UI when available;
- Files route works for moderate multi-selection;
- Folder route can enumerate 6,000–10,000+ files without a huge ActivityResult payload;
- in-app count no longer shows the near-2x duplication;
- in-app Select All remains memory-bounded;
- mixed-provider imports preserve valid items and classify failures correctly;
- previously observed 6,000–7,000 crash is absent.

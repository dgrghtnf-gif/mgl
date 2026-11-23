# 태블릿 파일 관리자 (Tablet File Manage)

태블릿 가로 화면에 맞춘 **큰 아이콘·큰 글씨 2단 파일 관리자** 앱입니다.
왼쪽에 분류(내부 저장소 / 다운로드 / 동영상 / 사진 / 문서 / APK / 휴지통)와 저장 공간 사용량,
오른쪽에 큰 카드형 파일 목록과 검색·정렬·새 폴더, 아래에 선택 작업 바(복사·이동·이름변경·공유·삭제)가 있습니다.

- Kotlin, minSdk 26 / targetSdk 34 / compileSdk 34
- 다크 슬레이트 테마 (`#0B1120` 배경, `#111827` 패널, `#3B82F6` 포인트)

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 저장소 권한 | Android 11+ 는 `MANAGE_EXTERNAL_STORAGE`(모든 파일 액세스) 설정 화면으로 안내, Android 8~10 은 런타임 저장소 권한 요청 |
| 탐색 | 실제 폴더 탐색, 상단 경로(빵) 표시, 뒤로 가기로 상위 폴더 이동 |
| 분류 | 내부 저장소·다운로드·휴지통은 폴더 탐색, 동영상·사진·문서·APK는 전체 저장소에서 확장자별 모아 보기 |
| 검색 | 현재 폴더 하위 재귀 검색 (350ms 디바운스) |
| 정렬 | 이름 / 최신 / 크기 (폴더 우선) |
| 다중 선택 | 카드 우클릭(길게 누르기) 또는 카드 우측 원형 체크로 선택, 하단 바에 개수·용량 표시 |
| 복사 / 이동 | 「복사」·「이동」 → 대상 폴더로 이동 → 「붙여넣기」 (이름 중복 시 `이름 (1).확장자`) |
| 이름 변경 | 선택 1개일 때 |
| 공유 | `FileProvider` 기반 단일/다중 공유 |
| 삭제 | 일반 폴더에서는 휴지통(`.pickle_trash`)으로 이동, 휴지통 화면에서는 완전 삭제(확인 대화상자) |
| 열기 | `FileProvider` + `ACTION_VIEW` + MIME 타입 (APK는 설치 화면) |
| 저장 공간 | `StatFs` 기반 사용 중 % / 여유 용량 바 |

## 프로젝트 구조

```
.
├── .github/workflows/build-apk.yml     # GitHub Actions: 서명된 APK 빌드 + 아티팩트 업로드
├── app/
│   ├── build.gradle.kts                # AGP 8.5.2, Kotlin 1.9.24, JDK 17
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/pickle/tabletfilemanager/
│       │   ├── MainActivity.kt         # 화면·권한·탐색·선택·파일 작업
│       │   ├── FileGridAdapter.kt      # 큰 카드 그리드 어댑터
│       │   ├── FileItem.kt             # 모델 · 정렬 모드 · 배지 색/라벨
│       │   └── FileOps.kt             # 목록·검색·스캔·복사·이동·MIME 유틸
│       └── res/
│           ├── layout/{activity_main,item_file,layout_sidebar_item,dialog_input}.xml
│           ├── drawable/*.xml          # 카드/버튼/배지/체크/용량바 배경
│           ├── values/{colors,strings,themes}.xml
│           ├── xml/file_paths.xml      # FileProvider 경로
│           └── mipmap-anydpi-v26/ic_launcher*.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── .gitignore
```

## APK 빌드 (GitHub Actions)

`main` 브랜치에 푸시하거나 **Actions → Build APK → Run workflow** 로 수동 실행하면 됩니다.

1. JDK 17 + Android SDK 34 + Gradle 8.7 준비
2. Gradle 래퍼가 없으면 자동 생성
3. `assembleRelease` (실패 시 `assembleDebug`) 로 APK 생성
4. 서명 확인 후 필요하면 `apksigner` 로 재서명
5. `TabletFileManager.apk` 를 워크플로 아티팩트(`TabletFileManager-apk`)로 업로드

### 서명 키

- 리포지토리에는 키를 커밋하지 않습니다(`.gitignore` 에 `keystore/`, `*.jks`).
- **기본 동작**: 워크플로가 키를 만들고 Actions 캐시에 보관해 다음 빌드에서 재사용합니다(앱 업데이트 시 서명 유지).
- **직접 키를 쓰려면**: 아래 시크릿을 등록하면 그 키로 서명합니다.
  - `KEYSTORE_BASE64` : 키스토어 파일을 base64 로 인코딩한 값
  - `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (선택)
- 서명이 바뀌면 기존 앱을 삭제하고 다시 설치해야 합니다.

## 로컬 빌드

```bash
# ANDROID_HOME 또는 local.properties(sdk.dir=...) 필요
./gradlew assembleDebug
```

## 참고

- `MANAGE_EXTERNAL_STORAGE` 는 개인용 사이드로드 앱 기준입니다. Google Play 에 올리려면 정책상 별도 사유가 필요합니다.
- 숨김 파일(`.` 로 시작)은 목록에서 숨깁니다. 휴지통 폴더 `.pickle_trash` 도 그래서 일반 탐색에는 보이지 않습니다.
- 동영상·사진·문서·APK 화면은 저장소 전체를 훑어 모으므로 파일이 아주 많으면 첫 표시에 몇 초 걸릴 수 있습니다(최대 4,000건까지 표시).

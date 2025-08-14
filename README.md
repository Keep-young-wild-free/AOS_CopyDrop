# CopyDrop Android

Mac CopyDrop과 연동하여 Wi-Fi 환경에서 클립보드를 공유하는 안드로이드 앱입니다.

## 기능

- **클립보드 동기화**: Mac과 Android 간 실시간 클립보드 공유
- **자동 서버 검색**: 같은 Wi-Fi 네트워크에서 Mac CopyDrop 서버 자동 검색
- **수동 연결**: IP 주소를 직접 입력하여 연결
- **백그라운드 동작**: 앱이 백그라운드에서도 클립보드 동기화 유지
- **보안**: 기본적인 데이터 암호화 및 인증 지원

## 요구사항

- Android 7.0 (API Level 24) 이상
- Wi-Fi 연결
- Mac에서 실행 중인 CopyDrop 서버

## 설치 및 실행

1. 프로젝트를 클론합니다:
```bash
git clone <repository-url>
cd AOS_CopyDrop
```

2. Android Studio에서 프로젝트를 엽니다.

3. 앱을 빌드하고 기기에 설치합니다.

## 사용법

1. **Wi-Fi 연결 확인**: Android 기기와 Mac이 같은 Wi-Fi 네트워크에 연결되어 있는지 확인합니다.

2. **Mac CopyDrop 실행**: Mac에서 CopyDrop 앱을 실행합니다.

3. **안드로이드 앱 실행**: CopyDrop Android 앱을 실행합니다.

4. **서버 검색**: 
   - "자동 검색" 버튼을 눌러 Mac 서버를 자동으로 찾거나
   - Mac의 IP 주소를 직접 입력하여 "수동 설정" 사용

5. **동기화 시작**: "클립보드 동기화 시작" 버튼을 눌러 연결을 시작합니다.

6. **클립보드 공유**: 이제 Mac에서 복사한 텍스트가 Android에서 붙여넣기 가능하고, 반대도 가능합니다.

## 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose
- **네트워크**: Retrofit2, OkHttp3
- **서비스 검색**: jmDNS
- **아키텍처**: MVVM with StateFlow
- **보안**: AES 암호화

## 주요 구성요소

### 네트워크 모듈
- `NetworkManager`: Mac 서버 검색 및 HTTP 통신 관리
- `CopyDropApi`: RESTful API 인터페이스 정의

### 서비스
- `ClipboardSyncService`: 포어그라운드 서비스로 클립보드 동기화 담당
- `ClipboardManager`: 클립보드 모니터링 및 관리

### UI
- `MainActivity`: 메인 화면 및 연결 관리
- `MainViewModel`: 앱 상태 관리

### 보안
- `SecurityManager`: 데이터 암호화 및 인증 토큰 관리

## 권한

앱에서 사용하는 주요 권한:

- `INTERNET`: 네트워크 통신
- `ACCESS_NETWORK_STATE`: 네트워크 상태 확인
- `ACCESS_WIFI_STATE`: Wi-Fi 상태 확인
- `FOREGROUND_SERVICE`: 백그라운드 동기화
- `POST_NOTIFICATIONS`: 동기화 상태 알림

## API 엔드포인트

Mac CopyDrop 서버와 통신하는 API:

- `POST /clipboard`: 클립보드 데이터 전송
- `GET /clipboard`: 클립보드 데이터 수신
- `POST /device/register`: 기기 등록
- `DELETE /device/unregister`: 기기 등록 해제
- `GET /ping`: 연결 상태 확인

## 문제 해결

### 연결이 안 되는 경우
1. Mac과 Android가 같은 Wi-Fi 네트워크에 연결되어 있는지 확인
2. Mac CopyDrop 서버가 실행 중인지 확인
3. 방화벽 설정 확인
4. IP 주소를 수동으로 입력해 보기

### 클립보드 동기화가 안 되는 경우
1. 앱에 클립보드 접근 권한이 있는지 확인
2. 백그라운드 앱 실행 권한 확인
3. 배터리 최적화에서 앱 제외

## 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다.

## 기여

버그 리포트나 기능 요청은 GitHub Issues를 통해 제출해 주세요.

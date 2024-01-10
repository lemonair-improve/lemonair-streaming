# Lemonair 🍋

![main-image](https://github.com/lem-onair/lenmonair-service/assets/121735319/56a5b3bf-ee2e-4122-8b09-a5a190aeb672)

```
스트리머가 실시간으로 방송을 송출하면
시청자들이 이를 시청하고 채팅과 후원으로 소통하는 서비스입니다.
```

## 소개 영상 (예정)

## | Service Architecture ⚙

![lemonair-architecture](https://github.com/lem-onair/lemonair-FE/assets/121735319/7d3d66d5-c6d6-4dc6-8906-2e92cd83212c)

## | Sequence Diagram 🔄

| ![sequence-diagram-1](https://github.com/lem-onair/lemonair-FE/assets/121735319/af029026-d460-4cb6-b61f-fc4effbad9eb) | ![sequence-diagram-2](https://github.com/lem-onair/lemonair-FE/assets/121735319/8ae538e2-c75e-4277-8c13-1c72a34c35ec) |
| --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |

## | 기술적 의사결정 🤔

<details>
<summary> WebMVC / WebFlux</summary>
<div markdown="1">

```
Spring WebFlux는 학습곡선이 가파르다는 장벽이 있었지만,
실시간 스트리밍 서비스의 특성상 많은 동시 사용자들과 실시간으로 처리해야 하는
데이터가 많이 요구되었습니다. 따라서, 비동기 논블로킹 방식으로 동작하여
높은 동시성과 확장성을 보장해야한다라는 것이 저희 조의 서비스에 핵심적인 가치였기 때문에
WebFlux를 선택하였습니다.
```

</div>
</details>

<details>
<summary> 방송 송출 프로토콜 관련 의사결정</summary>
<div markdown="1">

```
SRT Protocol은 아직은 1대1로 안정적인 퍼스트마일 딜리버리에 주로 사용되고있고,
방송 송출 지점과 스트리밍 서버간 거리가 멀지 않고,
많은 스트리머가 스트리밍 서버에 동시에 방송을 송출하는 경우
더 낮은 대역폭으로 전송하는 것이 유리하다.
레퍼런스가 많고 실제로 유튜브 스트리밍 등의 실제 서버와
비교 분석이 가능한 RTMP 를 선택하였습니다.
```

</div>
</details>

<details>
<summary> MA / SOA / MSA</summary>
<div markdown="1">

```
서비스의 핵심 기능인 스트리밍과 채팅 기능은
부하를 크게 일으키는 지점이라고 예상되기 때문에
독립적인 모듈로 분리하여 부하에 대한 부담을 분산시킴으로써,
고가용성과 확장성을 확보하기 위한 아키텍쳐가 필요했습니다.

자연스럽게 MA는 선택의 대상에서 제외되었고,
MSA와 SOA 중 어떤 아키텍처를 선택할지에 대한 고민에서
상대적으로 더 작은 단위로 분리하고, 데이터를 복제하는 개념인 MSA까지는
오버 엔지니어링이라는 판단을 하여, SOA를 선택하게 되었습니다.

SOA를 선택하여 서비스 간의 의존성을 최소화하면서 기능을 개발하고,
스트리밍과 채팅을 제외한 나머지 기능은 하나의 독립된 서비스로 분리하여
확장성과 유지보수성을 높이는 방향으로 설계하였습니다.
```

</div>
</details>

<details>
<summary> Sinks / Reactive Kafka / Reactive Rabbit MQ</summary>
<div markdown="1">

```
Sinks를 이용하여 구현해 본 결과 예상했던 대로 메모리 관련 이슈가 발생해
메세지를 유실하는 상황이 발생하여, RabbitMQ와 Kafka를 두고 고민하였고,
RabbitMQ는 높은 처리량보다는 지정된 수신인에게 원하는 방식으로 메시지를
신뢰성 있게 전달하는데에 초점이 맞추어져 있는 반면에
Kafka는 분산 아키텍처를 기반으로 하여 수평적으로 확장이 용이하고,
대용량의 메세지를 빠르게 처리할 수 있다는 점에서 고가용성을 보장한다는 측면이
Reactive manifesto의 핵심가치인 복원력과 유연성에 대한 가치를
만족한다는 점에서 Kafka를 선택하였습니다.
```

</div>
</details>

<details>
<summary> jmeter / gatling / k6</summary>
<div markdown="1">

```
채팅 서버 부하 테스트를 위해 3개의 테스팅 툴을 모두 사용해본 결과
Spike Test(동시 2000+명 접속)를 진행하면서 Jmeter는 gatling, k6와 비교했을 때
웹소켓 연결 요청 실패가 많았습니다. Jmeter가 한 명의 VU당 하나의 쓰레드를 할당하여
동작하기 때문에 연결을 유지해야하는 웹 소켓 테스트에서 특히 불리했을 것이라고 추측

또한 부하 상황에서 채팅이 얼마나 유실되는지에 대한 테스트를 진행함에 있어
gatling은 기본적으로 요청에 대한 응답을 테스트 지표로 제공하며 사용자 정의 지표를 작성하기
어려웠습니다.

간단한 테스트 시나리오 작성은 Jmeter의 GUI를 이용할 수 있어 Jmeter가 우세했지만,
복잡한 테스트 시나리오에서의 테스트 스크립트를 작성하는 데에는
팀원 모두에게 익숙한 언어인 javascript로 작성이 가능한 K6가 유리했습니다.

또한 K6는 Go 언어 기반으로 동작하여 JVM에 의존하는 Jmeter, Gatling보다 적은 리소스로
더 많은 VU로 테스트할 수 있었습니다.

K6가 기본적으로 제공하는 테스트 결과가 Jmeter, Gatling에 비해 부족하다는 느낌을 받았으나,
Jmeter, Gatling 또한 실시간으로 여러가지 지표에 대한 결과를 분석하려면 다른 모니터링 툴 과의
연동이 필수적이므로 단점으로 부각되지 않았습니다.
```

</div>
</details>

<details>
<summary> WebSocket(WebFlux) / Rsocket</summary>
<div markdown="1">

```
Rsocket의 성능 자체는 우수하여 채택할만 했지만 Rsocket을 도입했을 때 발생할
수 있는 side effect에 대해 참고할 수 있는 레퍼런스가 부족하였습니다.

성능면에서 비교해봤을 때,
WebFlux 에서 기본적으로 제공하는 WebSocket 또한 비동기적 특성을 갖고 있기 때문에
충분히 대용량에 대한 처리가 가능하다고 판단하였고, Back Pressure를 지원하지 않는다는
단점은 Kafka와 같은 Message Broker를 이용하여 Back Pressure와 비슷한 효과를
가져갈 수 있다는 판단하에 레퍼런스가 부족하고 러닝커브가 발생하는 RSocket보다는
안정적이고 성숙한 생태계를 가진 WebSocket을 선택하였습니다.
```

</div>
</details>

<details>
<summary> RDBMS / NoSQL</summary>
<div markdown="1">

```
실시간으로 생성 쿼리가 많이 발생하는 채팅 서버에 적합한 DB는 NoSQL이고,
많은 쿼리가 발생하지 않는 서비스 서버에 적합한 DB는 RDBMS라고 판단했지만,
SOA를 준수하는 차원에서 하나의 DB를 사용하기로 결정하였습니다.

따라서 NoSQL, RDBMS 둘 중 하나를 택해야 했는데,
분리되어 있는 서비스에서 중요한 것은 데이터의 일관성이라 결론을 내렸습니다.
무결성을 보장하는 MySQL DB를 선택하여 서비스들이
일관성 있는 데이터를 공유하도록 하였습니다.
비동기 프로그래밍 방식을 택한 프로젝트에서
MySQL이 동기 블로킹 방식으로 동작한다는 점은 치명적이었지만,
비동기 Non-Blocking 방식으로 I/O 할 수 있도록 R2DBC드라이버를 사용하여 극복하였습니다.
```

</div>
</details>

## | 기술스택 🧰

- ### SHARED
  <img src="https://img.shields.io/badge/springboot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white"><img src="https://img.shields.io/badge/Spring WebFlux-02303A?style=for-the-badge&logoColor=white"><img src="https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=MySQL&logoColor=white">
  <br /><br />
- ### STREAMING & TRANSCODING
  <img src="https://img.shields.io/badge/FFmpeg-007808?style=for-the-badge&logo=FFmpeg&logoColor=white"><img src="https://img.shields.io/badge/RTMP Netty Server-02303A?style=for-the-badge&logoColor=white"><br />
- ### CHATTING
  <img src="https://img.shields.io/badge/WebSocket-%23ED8B00?style=for-the-badge&logo=&logoColor=white"><img src="https://img.shields.io/badge/Apache Kafka-%23ED8B?style=for-the-badge&logo=Apache Kafka&logoColor=white"><img src="https://img.shields.io/badge/Zookeeper-FF6984?style=for-the-badge&logo=&logoColor=white"><br /><br />
- ### SERVICE(API)
  <img src="https://img.shields.io/badge/REDIS-DC382D?style=for-the-badge&logo=Redis&logoColor=white"><img src="https://img.shields.io/badge/Spring Security-6DB33F?style=for-the-badge&logo=Spring Security&logoColor=white"><br /><br />
- ### DEVOPS
  <img src="https://img.shields.io/badge/Amazon EC2-FF9900?style=for-the-badge&logo=Amazon EC2&logoColor=white"><img src="https://img.shields.io/badge/Amazon S3-569A31?style=for-the-badge&logo=Amazon S3&logoColor=white"><img src="https://img.shields.io/badge/Cloud Front-FF4F8B?style=for-the-badge&logo=Cloud Front&logoColor=white"><img src="https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=Docker&logoColor=white"><img src="https://img.shields.io/badge/NGINX-009639?style=for-the-badge&logo=NGINX&logoColor=white"><img src="https://img.shields.io/badge/Github Actions-2088FF?style=for-the-badge&logo=Github Actions&logoColor=white"><br /><br />
- ### TESTING TOOLS
  <img src="https://img.shields.io/badge/Apache JMeter-D22128?style=for-the-badge&logo=Apache JMeter&logoColor=white"><img src="https://img.shields.io/badge/Gatling-FF9E2A?style=for-the-badge&logo=Gatling&logoColor=white"><img src="https://img.shields.io/badge/k6-7D64FF?style=for-the-badge&logo=k6&logoColor=white"><img src="https://img.shields.io/badge/InfluxDB-22ADF6?style=for-the-badge&logo=InfluxDB&logoColor=white"><img src="https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=Grafana&logoColor=white"><br /><br />
- ### FRONTEND
  <img src="https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=React&logoColor=white">
  <br />
  <br />
  <br />

## | 트러블슈팅 🤬

### 1. 브라우저 새로고침 시에 웹소켓 연결이 끊기는 문제

### 2. Thumbnail 무한 업로드 문제

### 3. CloudFront Cache 관련 문제

<br />

### 해결 과정이 궁금하시다면 ?

### [Lemonair 트러블슈팅 노션 페이지](https://arrow-troodon-1c3.notion.site/96b72f2f066947e69d65560f748a7848?pvs=4)

<br />

## | 성능 개선 💪 (예정)

## | 멤버 👯‍♂️

- 이상문 : https://github.com/alaneelee
- 서병렬 : https://github.com/BYEONGRYEOL
- 강민범 : https://github.com/KangMinBeom
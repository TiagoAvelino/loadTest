# mqtt-producer

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_** Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/mqtt-producer-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)

Descoberta dos serviços mqtt: como que o broker entrega a fila correta atualmente

Validação do toekn via mqtt ?
Backend que gera o token acr.
Se não for gerada pelo ACR Não vai ser aceita.

Proxy do mqtt valida a token.

Token jwt precisa para conectar no canal do portal-bb tokenHorus
ABERTO/LOGADO
p: token
u:usuario - não logado uuid
iss: AREA ONDE FOI LOGADO / AREA RESTRITA
sub: uuid / MCI DO CLIENT

secret: do servidor de produção

IDA:
$id, $chanell

Volta : Eventos para todos os usuários (extractor)

Topicos de brodcast:
Publica algumas informações basicas para preparar o que será enviando/monitorar. (2-5 segundos)

Reconexão: não cai no mesmo servidor

websockt - cai - reconectar com a mesma token/ mesmo parametros por 5 segundos keep alive, (PENDING MESSAGE ARRAY)

Rendevous não tem retenção.

Chega no proxy mqtt (Aplicação mqtt) Todos os mqtts ouvirem do kafka - para receber a mensagem do usuário.

Kafka precisa saber para quem enviar e quando ???

Barramento Horus. HORUS_HANDEVOUS 4-10Servidores conectados com o handevous

o script guarda o historico das informações que precisa receber

pull request
push responde
Treta de consumo de partições

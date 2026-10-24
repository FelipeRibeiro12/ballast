# Imagem de produção para a VM ARM (aarch64) do Oracle Cloud (ADR-044).
#
# O jar é compilado antes, no runner amd64 do CI (`./mvnw package`), e aqui só é copiado. Bytecode
# Java não depende de arquitetura, então o mesmo jar roda no arm64. Por isso este arquivo não tem
# RUN: o buildx monta a imagem arm64 só copiando arquivo, sem executar nada da arquitetura alvo, e
# não precisa de QEMU. Um RUN aqui (apt-get, useradd, ...) obrigaria a emular arm64 no CI, que é
# lento e frágil. Compilar dentro da imagem pelo mesmo motivo também está fora.
FROM eclipse-temurin:25-jre-noble

WORKDIR /app
COPY ballast-*.jar /app/app.jar

# Usuário sem privilégio pelo número: criar usuário com nome exigiria um RUN.
USER 10001:10001

# Metade da memória do contêiner para o heap; o resto fica para metaspace, threads e buffers.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

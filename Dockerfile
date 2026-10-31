FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN sh ./mvnw -q compile exec:help
CMD ["sh", "-c", "sh ./mvnw -q exec:java && echo '===SUMMARY_CSV_START===' && cat results/summary.csv && echo '===SUMMARY_CSV_END==='"]

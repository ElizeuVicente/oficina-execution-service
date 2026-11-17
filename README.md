# Oficina Execution Service

Com `EXECUTION_MULTI_PART_ENABLED=true` e MongoDB replica set, a API interna `PUT /api/branches/{branch}/work-orders/{order}/authorization` recebe `paymentAttemptId` e `items` (`partId`, `quantity`) e marca a ordem pronta em uma transação MongoDB. `POST .../start` consome a reserva na mesma transação que inicia o reparo; `POST .../authorization/cancel` libera a reserva somente antes do início. As operações usam o token de serviço compartilhado e pressupõem um orquestrador confiável que já verificou o pagamento: o endpoint não consulta o provedor. Não expor em produção. A integração multipart permanece desabilitada por padrão fora do Compose integrado.

Na integração da Saga, `reservationId` identifica a reserva já confirmada, que precisa existir com os mesmos itens; o início consome essa reserva. O Compose integrado foi validado com evento de pagamento sintético, inclusive com uma peça e consumo de estoque. Pagamento real, autenticação de produção e compensação após início do reparo continuam pendentes.

Uma liberação de reserva ainda inexistente cria um registro de cancelamento durável, impedindo que uma solicitação atrasada a crie depois. Reservas vazias são permitidas para serviços sem peças. No Compose integrado da OS, o MongoDB usa um replica set local de um nó e a integração multipart é habilitada; esse arranjo é apenas de desenvolvimento.

Fundação local da Fase 4: API inicial de estoque e fila de diagnóstico por filial com MongoDB próprio. As rotas `/api/` exigem `X-Service-Token`, configurado por `EXECUTION_SERVICE_TOKEN` (mínimo de 32 caracteres). Health permanece acessível. O token compartilhado autentica o serviço, mas não autoriza filiais ou usuários individualmente; MongoDB não tem autenticação. Use somente em ambiente local isolado. A mensageria e a outbox estão implementadas; o limite opcional de filiais não substitui autorização individual.

Execute `mvn test` com Java 17 e Maven; `docker compose up --build` inicia a API em `127.0.0.1:8082` e o MongoDB apenas na rede interna. Em `/api/branches/{branch}/parts/{part}/stock`, `POST` com `{"available":5}` cria o estoque e `GET` consulta. `PUT /reservations/{id}` com `{"quantity":2}` reserva; `POST /reservations/{id}/release` libera. Repetições idênticas não alteram o saldo novamente. A versão otimista do documento evita gravações concorrentes silenciosas: uma disputa retorna HTTP 409 e o chamador deve consultar e repetir com o mesmo identificador. Não há garantia de atomicidade entre documentos ou publicação de eventos nesta etapa.

`POST /reservations/{id}/consume` confirma a baixa definitiva de uma reserva; a repetição é idempotente, e reserva consumida não pode ser liberada. A quantidade é descontada ao reservar, portanto o consumo não desconta novamente. Documentos MongoDB antigos sem o campo de consumo são tratados como reservas ainda não consumidas. Os testes de MongoDB usam Testcontainers e são pulados se Docker não estiver disponível.

Reserva experimental de até 100 peças: com `EXECUTION_MULTI_PART_ENABLED=true` e MongoDB configurado como **replica set**, `PUT /api/branches/{branch}/stock-reservations/{reservation}` aceita `{"items":[{"partId":"UUID","quantity":2}]}`. O conjunto de peças fica vinculado à chave de reserva e grava junto dos saldos em transação; mudança dos itens no retry gera conflito. Se alguma peça falhar, nenhuma reserva dessa transação é confirmada. `POST .../{reservation}/release` e `POST .../{reservation}/consume` finalizam o conjunto atomicamente, aceitam repetição da mesma operação e rejeitam a finalização oposta. O Compose integrado da OS usa replica set de um nó e habilita a integração. Falhas de concorrência podem exigir repetição da mesma requisição. A restrição por filial é opcional e usa credencial compartilhada.

Para diagnóstico, `POST /api/branches/{branch}/work-orders/{order}` inclui a OS; `GET /api/branches/{branch}/work-orders?limit=50` lista a fila da filial, em ordem de chegada. `POST .../{order}/diagnosis/start` inicia o diagnóstico e `POST .../{order}/diagnosis/complete` com `{"description":"..."}` o conclui. A OS fica aguardando pagamento. A autorização da Saga marca a OS como pronta após confirmação de pagamento e reserva; os endpoints de início e conclusão verificam a sequência. Nenhum pagamento real é validado por este serviço nesta etapa.

Para integrações com repetição, `PUT /api/branches/{branch}/work-orders/{order}` cria a ordem se necessário ou retorna a existente sem reiniciar o diagnóstico. Configure o token no ambiente antes de iniciar o Compose e envie-o no cabeçalho `X-Service-Token`. Fora do laboratório serão necessários TLS, gestão de segredos e autorização por identidade e filial.
# Catálogo de peças experimental

`PUT /api/branches/{branch}/parts/{part}/catalog` cadastra descrição e preço unitário no MongoDB da Execução; `GET` consulta pela mesma filial e identificador. As rotas exigem o token interno existente. O estoque permanece em sua coleção própria. A OS pode usar este catálogo na integração; a migração dos dados reais não ocorre automaticamente.

`PUT` e `GET /api/branches/{branch}/services/{service}/catalog` disponibilizam igualmente descrição e preço de serviços por filial. A migração e a reconciliação de preços históricos reais ainda precisam ser realizadas.

Para migração, os endpoints de peças e serviços aceitam `PUT` em `/catalog/import`: repetição com os mesmos dados é idempotente; descrição ou preço divergente retorna 409 sem sobrescrever o destino. As rotas regulares de cadastro continuam permitindo alteração de preço. A importação não migra nem altera quantidades de estoque.

# Limite opcional de filiais

`EXECUTION_ALLOWED_BRANCHES` pode conter UUIDs separados por vírgula para restringir as rotas internas da Execução a essas filiais. Vazio mantém o comportamento anterior sem restrição. Essa lista limita o token compartilhado como um todo; não autentica usuários individuais, não substitui TLS e não oferece autorização granular por serviço. Configure a rede interna adequadamente.

# CI/CD e Kubernetes — configuração da Fase 4

O workflow `.github/workflows/ci-cd.yml` executa `mvn verify`, testes, exige 80% de linhas no JaCoCo e publica o relatório como artefato. Em 17/11/2026, o relatório local com Docker apresentou 250/303 linhas (82,51%). O relatório é gerado em `target/site/jacoco/index.html`; a execução remota ainda precisa ser demonstrada. O CodeQL executa análise estática no GitHub Actions; a primeira execução deve ser conferida na aba Actions e os alertas na aba Security. O SonarCloud é opcional e exige `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION` e `SONAR_TOKEN`; sem configuração ela é pulada e não comprova o requisito de qualidade.

O deploy é acionado manualmente via `workflow_dispatch` na `main` com `deploy=true`, após os testes: publica imagem GHCR por SHA, aplica `k8s/` e aguarda rollout. Antes, configure o environment `homolog`, `KUBE_CONFIG_B64`, `K8S_NAMESPACE` e o secret Kubernetes `oficina-execution-config` com URI MongoDB e token. MongoDB com replica set para transações, acesso do runner ao cluster e credenciais para pull de imagem privada são pré-requisitos externos. Não coloque segredos em arquivos versionados. Ainda é necessário executar e evidenciar o pipeline e o deploy real.

# Documentação da API

Com o serviço iniciado, o OpenAPI está em `/v3/api-docs` e a interface Swagger em `/swagger-ui/index.html`. As rotas `/api/` exigem `X-Service-Token`. Documentação não deve ser exposta publicamente sem proteção no ambiente implantado.


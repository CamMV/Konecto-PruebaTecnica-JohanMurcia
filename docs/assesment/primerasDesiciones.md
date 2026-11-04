## Retrieval:
* Búsqueda especifica por "unique Base Part number" - Sparse retrieval (BM25)
* Retorno de explicación semántica para recomendar partes por si no se tiene la identificación unica, dados los rasgos técnicos requeridos.  - Dense Retrieval ()
* Usar hybrid search o validar si se puede usar graph-bsaed retrieval


## BackEnd:
* REST servicio con FastAPI
* LangChain
* session_id para mantener una conversación
* Historial persistido
* Casos frontera
* PostgreSQL corriendo en un docker
* pgvector

## Contrato:

POST /api/conversation
Request: { "session_id": "<conversation id>", "query": "<user's question>" }
Response: { "session_id": "<conversation id>", "answer": "<agent's answer>", ... }

### opcionales
GET /api/sessions conversations: id, title, date
GET /api/sessions/{id}/messages history to resume a conversation
POST /api/auth/login optional, see section 7:
{ email, password } -> { token }

## Mobile App:

* Native kotlin, usar mvvm patron, tener en cuenta Eventual conectivity, cache, persistencia con sql, conexión con back y Jetpack compose
* Cuatro screens, login, conversación, chat y debug(desarrolladores)

## Preguntas:
* El sistema debe responder en un idioma en especifico? o tiene que ser multilingüe
* Se pueden usar otros subservicios de lang como lo serian langgraph y langsmith?
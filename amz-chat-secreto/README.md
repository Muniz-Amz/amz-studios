# AMZ Chat Secreto

Pagina escondida para conversa privada com login por usuario e senha, texto, imagem e video.

## Link

Depois de publicado no GitHub Pages:

`https://muniz-amz.github.io/amz-studios/amz-chat-secreto/`

## Como ativar

1. Crie um projeto no Supabase.
2. Abra o SQL Editor e rode o arquivo `supabase.sql`.
3. Copie a `Project URL` e a `anon public key`.
4. Preencha `config.js`:

```js
supabaseUrl: "https://SEU-PROJETO.supabase.co",
supabaseAnonKey: "SUA_ANON_KEY"
```

## Corrigir erro de Storage

Se aparecer `Direct deletion from storage tables is not allowed`, rode o arquivo
`supabase-storage-hotfix.sql` no SQL Editor do Supabase.

Se ativar a criptografia e o upload de midia falhar por tipo de arquivo, rode
`supabase-e2ee-hotfix.sql` no SQL Editor do Supabase.

## Limpeza automatica no Supabase

Para deixar o Supabase rodando a limpeza sozinho a cada 10 horas, rode
`supabase-cleanup-schedule-10h.sql` no SQL Editor depois de rodar `supabase.sql`.

Se o Supabase reclamar que `pg_cron` nao existe, ative o modulo em
`Database > Extensions` ou `Integrations > Cron` e rode o arquivo de novo.

## Privacidade

- A pagina nao aparece no menu do site.
- A pagina nao entra no sitemap.
- O HTML usa `noindex` para pedir que Google nao indexe.
- O login usa hash de senha no `config.js`, sem deixar a senha escrita em texto puro.
- Os dois usuarios usam a mesma chave privada da conversa para cair na mesma sala criptografada.
- Texto, imagens e videos sao criptografados no navegador antes de ir para o Supabase.
- O banco usa RLS e so libera mensagens da sala quando o navegador envia a chave gerada pela chave privada.
- Mensagens e midias antigas deixam de aparecer depois de 24h.
- O SQL apaga mensagens antigas quando novas mensagens entram, quando o app chama a rotina ou quando o agendamento de 10h estiver ativo.
- Midias antigas entram em uma fila de limpeza e sao apagadas pela API de Storage quando alguem abre o chat.

## Criptografia ponta-a-ponta

- A chave privada da conversa nao deve ser enviada por mensagem, print ou commit.
- A chave privada nao fica salva no site; ela fica somente na sessao da aba do navegador.
- O Supabase ainda consegue ver metadados como horario, tamanho do arquivo, perfil e sala em hash.
- Mensagens antigas enviadas antes da criptografia ficam em outra sala/hash e expiram pela limpeza de 24h.
- Depois de ativar, rode `supabase-e2ee-hotfix.sql` para aceitar arquivos criptografados no Storage.

## Trocar usuarios e senha

Os usuarios ficam em `config.js`, dentro de `accounts`.

Para gerar um novo hash de senha, use:

```js
const salt = "amz-studios-private-chat";
const username = "muniz";
const password = "SUA_SENHA";
const bytes = new TextEncoder().encode(`${salt}:${username}:${password}`);
const hash = await crypto.subtle.digest("SHA-256", bytes);
console.log([...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, "0")).join(""));
```

## Limites atuais

- Imagens ate 10 MB.
- Videos ate 60 MB.
- Conversas expiram depois de 24 horas.
- Cada imagem ou video enviado aparece com botao `Salvar midia`.
- Usuarios fixos: `muniz` e `monteiro`.

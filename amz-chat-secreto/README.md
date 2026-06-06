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

## Privacidade

- A pagina nao aparece no menu do site.
- A pagina nao entra no sitemap.
- O HTML usa `noindex` para pedir que Google nao indexe.
- O login usa hash de senha no `config.js`, sem deixar a senha escrita em texto puro.
- Os dois usuarios usam a mesma senha da sala para cair na mesma conversa.
- O banco usa RLS e so libera mensagens da sala quando o navegador envia a chave gerada pela senha.
- Mensagens e midias antigas deixam de aparecer depois de 24h.
- O SQL cria uma limpeza automatica que apaga mensagens e arquivos antigos quando novas mensagens entram ou quando o app chama a rotina.

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

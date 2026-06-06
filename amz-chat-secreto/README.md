# AMZ Chat Secreto

Pagina escondida para conversa privada entre dois perfis, com texto, imagem e video.

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

## Privacidade

- A pagina nao aparece no menu do site.
- A pagina nao entra no sitemap.
- O HTML usa `noindex` para pedir que Google nao indexe.
- Use um codigo de conversa longo, porque ele vira a chave privada da sala.
- O banco usa RLS e so libera mensagens da sala quando o navegador envia a chave gerada pelo codigo.

## Limites atuais

- Imagens ate 10 MB.
- Videos ate 60 MB.
- Perfis fixos: `Muniz` e `Amigo`.

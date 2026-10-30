# Escala (esqueleto)

Captura a tela (MediaProjection), aplica upscaling na GPU (GLES 3.0) e mostra o resultado num overlay.
Sem dependências externas (só APIs do Android). NÃO foi compilado nem testado ainda.

## Compilar
- **Android Studio:** abrir a pasta `escala` e rodar `app`.
- **Só pelo celular:** subir a pasta para um repositório no GitHub; a aba Actions (workflow `build-apk`)
  gera o `app-debug.apk` como artefato.

## Usar
1. Abra o app, toque em "1. Permitir sobreposição de tela".
2. Escolha filtro, resolução da captura e overlay; toque em "2. Iniciar" e autorize a captura.
3. A notificação mostra fps e resolução. "Parar" na notificação ou no app encerra.

## Pontos de atenção
- **Laço de captura:** a captura inclui o próprio overlay. Em "Tela cheia" a imagem congela, a menos que
  o Android seja 14+ e você escolha "Compartilhar um app" (captura só o jogo). "Miniatura" serve para
  validar o pipeline em qualquer versão.
- **Resolução da captura:** só há ganho real se casar com a resolução interna do jogo (ex.: jogo/emulador
  renderizando a 50–67% da tela).
- **Opacidade:** FLAG_NOT_TOUCHABLE limita a opacidade do overlay no Android 12+.
- **Conteúdo protegido** (FLAG_SECURE/DRM) aparece preto na captura.

## Próximos passos
1. Medir fps/temperatura no aparelho com cada filtro.
2. Troca de FLAG_NOT_TOUCHABLE por serviço de acessibilidade com região de toque vazia.
3. FSR1 (EASU+RCAS) no lugar do bicúbico.
4. Passe de interpolação: fluxo óptico em 1/4 de resolução + warp/blend (GLES 3.1 compute ou Vulkan).

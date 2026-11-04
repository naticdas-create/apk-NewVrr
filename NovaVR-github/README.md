# NovaVR (Android) — navegador flutuante com câmera, mãos e YouTube

## Montar o projeto (5 minutos)
1. Android Studio → New Project → **Empty Views Activity**. Nome `NovaVR`, pacote `com.novavr`, Kotlin, Min SDK 26.
2. Copie `app/src/main/java/com/novavr/*.kt` para o mesmo caminho do seu projeto (apague o MainActivity.kt padrão).
3. Substitua o `AndroidManifest.xml` pelo deste pacote (mantenha o `@style/Theme...` se o seu tema padrão tiver outro nome).
4. Cole as linhas de `app/dependencies.txt` em `dependencies { }` do `app/build.gradle.kts`, depois Sync.
5. Baixe o modelo de mãos e coloque em `app/src/main/assets/hand_landmarker.task`:
   https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
6. Rode em um celular real (não funciona em emulador).

## Como usar
- Coloque o celular no VR Box na horizontal. O fundo é a câmera traseira.
- **Mão:** aponte com o polegar e o indicador (o ponto branco). **Junte os dedos (pinça)** para clicar. Pinça e arrastar rola a página.
- **Olhar:** sem mão visível, fixe o ponto em um botão por 1,2 s.
- Barra: ← voltar, ▶ YouTube, ABC teclado, ⌖ recentrar, ✕ sair.
- No teclado, "Ir" abre o endereço, ou pesquisa no YouTube se for texto comum.

## Limites conhecidos
- Sem correção de distorção das lentes (próxima etapa: OpenGL com shader de barril).
- A câmera e a tela têm um pequeno atraso e o cursor da mão fica no plano da câmera, não na profundidade da janela.
- **Roblox:** não existe como fazer o app do celular entrar em modo VR. O que dá é espelhar a tela (MediaProjection) em uma quarta janela; fica como etapa 2 e o jogo precisa de controle Bluetooth.

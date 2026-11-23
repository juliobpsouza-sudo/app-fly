# Changelog

## 0.5.9

- **Mandar pra outro aparelho, passando pelo PC:** no topo do app do celular, toque no nome do destino e escolha o PC ou outro celular/tablet pareado com o mesmo PC. O PC faz a ponte, e o aparelho de destino pede Permitir antes de receber.
- **Escolher o aparelho no PC:** a aba "Enviar pro celular" e o portal mostram os aparelhos pareados. Você escolhe pra qual vai, e as atualizações do app vão pra todos.
- **Nome do aparelho:** em Configurações, cada aparelho tem um nome, que é como ele aparece no PC e nos outros aparelhos.
- **"Do PC" virou "Receber".**
- **Configurações do celular em cartões:** animação do gesto de empurrar, cada nível de força explicado e uma barra de força no teste.
- **Conexão mais firme depois de religar o PC:**
  - erro de rede tenta de novo uma vez só, e cada envio usa uma conexão nova;
  - a conexão de fundo espera no máximo 10 s entre as tentativas e acorda quando o app abre;
  - o PC só mostra "conectado" com a conexão de fundo ativa;
  - o log do PC registra o motivo de cada envio que falha, e um envio parado por 30 s é encerrado;
  - o Fly volta a se registrar pra abrir com o Windows, se a entrada sumir.

## 0.5.4

- Nova aba **Configurações** na janela do PC, com a seção do portal: liga e desliga, tempo pra responder o pop-up (10 s, 15 s, 30 s ou 1 min) e botão "Testar portal".
- Botão do topo renomeado para "Baixar app celular (QR)".
- App do celular: o topo agora tem só **↻**, **Do PC**, **Link** e **⚙ Configurações**. O **↻** lê a galeria de novo sem reiniciar o app. O **Sair** (com confirmação) e o envio de arquivos pro PC foram pra tela Configurações.

## 0.5.3

Primeira versão pública.

- Do celular pro PC: galeria com arremesso por gesto ou empurrão, Compartilhar, prévia na hora e original com barra de progresso, cartões que se arrastam, fecham e copiam, janela de fotos recebidas.
- Do PC pro celular: aba "Enviar pro celular" e portal luminoso no canto da tela, com confirmação antes de enviar. O celular pede permissão antes de receber.
- Instalador do PC com o app do celular dentro; QR pra baixar o APK e aba de atualizações.
- Parear por QR com código único, ou digitando o endereço e o código.
- Funciona na mesma rede ou pelo Tailscale.

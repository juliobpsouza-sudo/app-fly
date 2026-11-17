<p align="center"><img src="pc/assets/icon.png" width="128" alt="Fly"></p>

# Fly

https://github.com/user-attachments/assets/24d6dd51-8f36-41df-afda-1658128a7776

Arremesse foto, arquivo, link e texto entre o celular Android e o PC com Windows. Escolha uma foto no celular, arraste pra cima, e ela pousa na tela do computador. Do PC pro celular, solte um arquivo numa barra luminosa no canto da tela.

**[Baixar a versão mais nova](../../releases/latest)**

## O que ele faz

**Do celular pro PC**
- Abra o app no celular, escolha uma foto e **arraste pra cima** (ou empurre o celular pra frente, se ligar o gesto em Ajustes). A foto aparece na tela do PC na hora, por uma prévia leve; o original chega logo depois, com barra de progresso.
- No PC cada foto vira um cartão: dá pra arrastar pro lado, fechar, **copiar** (arquivo e imagem) ou arrastar o arquivo pra uma pasta ou programa.
- Pelo **Compartilhar** de qualquer app do celular também vai: fotos, arquivos e links. Um link vira um cartão com botão "Abrir link". Ele não abre sozinho.
- A janela de fotos recebidas no PC guarda tudo que já chegou, agrupado por dia. Os arquivos ficam em `Downloads\Fly`.

**Do PC pro celular**
- **Portal no canto da tela:** uma barra dourada discreta no canto de baixo à direita, em cima da data e hora, sempre por cima das outras janelas. Arraste um arquivo, PDF, link ou texto até ela, solte, e responda "Sim, enviar" no pop-up. Dá pra ligar e desligar, mudar o tempo pra responder e testar o pop-up na aba **Configurações** da janela principal (ou no menu do ícone da bandeja).
- Também dá pra soltar na aba **Enviar pro celular** da janela principal.
- O celular avisa, e você escolhe **Permitir** ou **Recusar**. Nada é baixado sem a sua permissão.

**Atualizar o app do celular sem baixar nada à mão**
- O instalador do PC já leva o app do celular dentro. Depois de instalar, o Fly mostra um **QR**: aponte a câmera do celular e o download do app começa. O QR fica sempre disponível no menu da bandeja ("Instalar/atualizar o app no celular (QR)").
- Quando sair app novo, a aba **Atualizações** envia o APK pro celular pelo mesmo caminho dos arquivos.

## Como rodar (Windows 10 e 11, Android 10 ou mais novo)

1. Em [Releases](../../releases/latest), baixe `Fly-Setup-<versão>.exe` e rode. O Windows vai avisar (SmartScreen), porque o instalador **não é assinado**: clique em "Mais informações" e "Executar assim mesmo". Ele pede permissão de administrador pra criar a regra do firewall.
2. Na primeira vez o Fly mostra o QR do app do celular. Aponte a câmera, baixe e instale o APK (o Android pede pra liberar a instalação de apps daquele navegador).
3. Clique em "Já instalei o app: parear o celular". Abra o Fly no celular, toque em **Escanear QR do PC** e aponte pro QR que aparece. A janela fecha sozinha quando o celular conecta.
4. **Sem Wi-Fi em comum?** Instale o [Tailscale](https://tailscale.com) no PC e no celular e ligue nos dois. O Fly também funciona por ele.

O Fly abre junto com o Windows (dá pra desligar no menu da bandeja).

## Segurança

- O celular só fala com o PC depois de parear. A chave vem pelo QR e fica só em `%APPDATA%\zarpa\config.json` no PC e no celular. Sem a chave, o PC recusa.
- O PC só aceita conexão da rede local e do Tailscale. A regra de firewall "Fly" vale só pra rede privada e só pra esses endereços.
- Limite de 2 GB por arquivo e sempre deixa 500 MB livres no disco. 15 senhas erradas em 1 minuto bloqueiam o endereço por 5 minutos.
- Arquivos recebidos só abrem com dois cliques se forem de tipos seguros (foto, vídeo, PDF, Office). `.exe`, `.bat`, `.lnk` e parecidos só aparecem na pasta.
- **Limitações:** o tráfego é HTTP puro, então fora do Tailscale ele passa sem criptografia pela sua rede. Evite usar em Wi-Fi de outras pessoas sem o Tailscale. A chave é uma só pra todos os celulares pareados ("Desparear todos os celulares", no menu da bandeja, gera uma nova).

## Pastas e nomes internos

- Dados do PC: `%APPDATA%\zarpa` (chave, fila de envio, log). Fotos recebidas: `Downloads\Fly`.
- O app já se chamou Zarpa. Por dentro o nome antigo continua de propósito (pacote Android `app.zarpa`, cabeçalhos `X-Zarpa-*`, pasta `zarpa`), pra quem já tinha o app não perder o pareamento ao atualizar.

## Compilar do código

Precisa de Node.js, JDK 17, Android SDK 35 (plataforma e build-tools 35.0.0) e Gradle 8.9. A ordem importa: o instalador embute o APK.

1. **App do celular:** em `android/`, com `JAVA_HOME` e `ANDROID_HOME` definidos, rode `gradle assembleDebug`. Copie `android/app/build/outputs/apk/debug/app-debug.apk` para `Zarpa.apk`, na raiz do projeto.
2. **Instalador do PC:** em `pc/`, rode `npm ci` e depois `npm run dist`. O passo `predist` copia o `Zarpa.apk` pra dentro do pacote e anota a versão. O instalador sai em `pc/dist/`.
3. **Só rodar, sem instalar:** em `pc/`, `npm ci` e `npx electron .`

Antes de uma nova versão, suba `versionCode` e `versionName` em `android/app/build.gradle.kts` e `version` em `pc/package.json`.

## Licença

[MIT](LICENSE).

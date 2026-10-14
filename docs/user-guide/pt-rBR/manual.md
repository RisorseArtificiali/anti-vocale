# Manual do usuário do Anti-Vocale

O Anti-Vocale transcreve mensagens de voz no seu aparelho Android, inteiramente offline. O áudio nunca sai do telefone: a transcrição roda localmente com modelos de IA abertos, sem conta, sem serviço em nuvem, sem telemetria.

Manual atualizado para a versão 1.15.

## Conteúdo

1. [Primeiros passos](#primeiros-passos)
2. [Escolhendo um modelo](#escolhendo-um-modelo)
3. [Transcrevendo: o fluxo do dia a dia](#transcrevendo-o-fluxo-do-dia-a-dia)
4. [Mensagens de voz do WhatsApp, automaticamente](#mensagens-de-voz-do-whatsapp-automaticamente)
5. [Resultados: rótulos, formatos e janela flutuante](#resultados-rótulos-formatos-e-janela-flutuante)
6. [Áudio longo, fila e novas tentativas](#áudio-longo-fila-e-novas-tentativas)
7. [Modelos da comunidade e importações](#modelos-da-comunidade-e-importações)
8. [Tasker e automação](#tasker-e-automação)
9. [Configurações por aplicativo](#configurações-por-aplicativo)
10. [Privacidade](#privacidade)
11. [Solução de problemas](#solução-de-problemas)
12. [Perguntas frequentes](#perguntas-frequentes)

As seções 8a, 8b e 8c cobrem as superfícies de automação: o monitoramento programado de pastas, o offload remoto e o provedor Open Transcribe.

## Primeiros passos

1. Instale o Anti-Vocale pela sua loja (Google Play ou F-Droid) ou por um APK de versão no GitHub.
2. Abra o aplicativo uma vez. Na aba **Modelo** você verá os modelos embutidos disponíveis para download.
3. Baixe um modelo. Para a maioria das pessoas, a primeira escolha recomendada é o **Parakeet TDT (stock int8, 640 MB)**: rápido, leve e cobre 25 idiomas europeus.
4. Para transcrever, compartilhe uma mensagem de voz de qualquer aplicativo de mensagens (WhatsApp, Telegram, Signal e outros) com o Anti-Vocale. Uma notificação aparece durante o processamento e depois uma segunda notificação com o texto.
5. Toque na notificação de resultado para copiar, compartilhar ou enviar o texto de volta para a conversa de origem.

Nenhuma configuração adicional é necessária. Todo o resto abaixo é opcional.

## Escolhendo um modelo

Os modelos diferem em tamanho, velocidade, cobertura de idiomas e precisão. A aba Modelo mostra os dados essenciais em cada cartão antes do download. Uma orientação rápida:

| Modelo | Tamanho | Idiomas | Observações |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 europeus | Rápido e leve; a recomendação padrão |
| Parakeet TDT SmoothQuant | 862 MB | 25 europeus | Mais preciso, mais pesado; exige mais RAM |
| Whisper Turbo | 988 MB | 101 | Melhor equilíbrio da família Whisper |
| Whisper Medium | 903 MB | 101 | Mais lento que o Turbo, não é melhor para a maioria dos áudios |
| Whisper Small | 358 MB | 101 | O Whisper mais leve; qualidade decente |
| Whisper Distil Italian | 938 MB | Apenas italiano | Melhor precisão em italiano do conjunto embutido |
| Qwen3-ASR | 938 MB | Multilíngue | Arquitetura alternativa |
| Nemotron streaming | 640 MB | Multilíngue | Mostra o texto enquanto você fala (streaming) |
| GigaAM v3 | 326 MB | Russo | Especialista em russo |

Regras práticas:
- Se você transcreve principalmente um idioma, um modelo especialista (Distil Italian, GigaAM) supera um generalista do mesmo tamanho.
- Se o seu telefone tem 4 GB de RAM ou menos, prefira modelos abaixo de 500 MB.
- Os modelos Gemma (listados separadamente na aba Modelo) são modelos de linguagem maiores que também transcrevem. São interessantes para experimentação, mas mais pesados e lentos que os modelos de ASR dedicados.

## Transcrevendo: o fluxo do dia a dia

- Compartilhe uma mensagem de voz com o Anti-Vocale. O processamento começa imediatamente, mesmo com a tela apagada.
- A notificação de resultado oferece: **Copiar**, **Compartilhar** e, quando o aplicativo de origem é suportado, **Enviar para [App]**, que cola o texto diretamente na conversa de onde veio a mensagem de voz.
- Com a Auto-Cópia ativada (Configurações), o texto já está na sua área de transferência quando a notificação chega; a notificação avisa isso.
- Toda transcrição fica na aba **Logs** com o modelo usado, a duração e o tempo de processamento. Toque e segure uma entrada para tentar novamente, copiar, excluir ou relatar um resultado ruim por e-mail; **Re-transcrever** roda o mesmo áudio por um modelo diferente, à sua escolha.
- Com um modelo **Whisper ou SenseVoice**, cada transcrição expandida traz um selo de idioma: a bandeira do idioma que o modelo detectou, com um toque para conferir ou fixar quando a detecção estiver errada (modelos pequenos erram às vezes). As outras famílias de modelos não informam o idioma detectado, então o selo fica desligado para elas; a opção em Configurações avisa isso e permanece desabilitada.
- Com o Auto-Salvamento (Configurações), cada transcrição também é gravada como arquivo .txt em uma pasta que você escolher.

## Mensagens de voz do WhatsApp, automaticamente

O Anti-Vocale pode transcrever novas mensagens de voz do WhatsApp no momento em que chegam, sem nenhum toque. O texto vai para a aba Histórico e para uma notificação de resultado; se o Android bloquear uma inicialização em segundo plano (por exemplo, com a tela apagada), uma notificação de um toque inicia a transcrição no seu lugar.

A configuração leva um minuto, em **Configurações > Mensagens de voz**:

1. Ative a **Transcrição de mensagens de voz** e conceda o acesso ao áudio quando o Android pedir. Se você recusou duas vezes, o Android não mostra mais o diálogo; a dica vermelha que aparece no lugar abre a página do sistema onde a permissão ainda pode ser concedida.
2. Escolha a pasta Voice Notes do WhatsApp uma única vez pelo seletor guiado. Uma pasta errada é recusada na hora; você pode mudar ou remover a pasta quando quiser no mesmo cartão.
3. Opcional: ative também o **reconhecimento do remetente** e conceda o acesso às notificações. A linha dos Logs passa então a ser rotulada com o nome do remetente.

Duas regras honestas governam os rótulos. Um nome aparece apenas quando a própria notificação da mensagem o confirma, ou seja, a notificação chegou enquanto o recurso estava ligado e o tamanho informado corresponde ao áudio: nomes nunca são adivinhados. E mensagens baixadas manualmente (com o download automático desligado) ficam sem rótulo, porque a notificação delas é velha demais para ser confiável. A lista de espera das notificações recentes vive apenas na memória e desaparece quando o recurso está desligado.

A privacidade é o mesmo contrato de sempre: com a detecção ligada, o aplicativo lê apenas a listagem da pasta de mensagens de voz do WhatsApp; o áudio de uma mensagem é lido apenas para transcrevê-lo, inteiramente neste aparelho; com o recurso desligado, nada é lido.

Uma ressalva, dita claramente: em telefones com gerenciadores de bateria agressivos, mensagens que chegam enquanto o sistema suspendeu o aplicativo podem ser perdidas até ele acordar. Se você depende do modo automático, excluir o Anti-Vocale da otimização de bateria o torna confiável. Por enquanto, apenas o WhatsApp; o Telegram está no roteiro.

## Resultados: rótulos, formatos e janela flutuante

- **Rótulos de falante**: ative em Configurações e as transcrições de conversas recebem atribuição de falante por frase, exibida como turnos de SPEAKER no resultado e em todas as exportações.
- **Compartilhar como mais que texto**: em uma linha de resultado, compartilhe como texto simples, texto com marcações de tempo, SRT ou VTT. Os formatos com tempo exigem uma transcrição que carregue marcações. O "Compartilhar como…" é opcional e se ativa em Configurações.
- **Picture-in-Picture**: enquanto uma transcrição roda, uma janela flutuante pode mostrar o texto aparecendo ao vivo, para você continuar usando outros aplicativos.
- **Re-transcrever**: tente novamente qualquer entrada com um modelo diferente direto da sua linha, e compare.

## Áudio longo, fila e novas tentativas

- Qualquer duração de áudio funciona com qualquer modelo: gravações mais longas são divididas e reunidas automaticamente. (Versões antigas tinham um limite de 6:40 com o Parakeet; isso acabou.)
- Compartilhe várias mensagens em sequência: elas entram em fila. Cada item da fila pode ser cancelado individualmente pela sua notificação enquanto outra transcrição roda.
- Uma transcrição que falhou pode ser repetida com um toque na aba Histórico.

## Modelos da comunidade e importações

O catálogo embutido não cobre todos os idiomas. O Anti-Vocale vem com um catálogo da comunidade de modelos extras que você importa com dois toques: aba Modelo, Avançado, ONNX Sherpa, Importar do catálogo, filtre pelo seu idioma, toque no modelo, confirme. O catálogo cresce continuamente e hoje tem mais de 30 entradas validadas: árabe (dialetal, completo e leve), alemão (streaming e um fine-tune do Whisper), espanhol (streaming e Moonshine leve), alemão suíço, hebraico, persa, russo, vietnamita, coreano, ucraniano, seis idiomas índicos (bengali, gujarati, híndi, marata, tâmil, télugo), Canary Flash para inglês e francês, SenseVoice, Dolphin, Orukeet (um fine-tune do Parakeet que cobre 24 idiomas, incluindo o italiano) e um Whisper Tiny multilíngue para pouca RAM. Se o seu idioma falta, abra uma issue: é assim que entradas são adicionadas.

Usuários avançados também podem:
- importar um modelo pela URL de um repositório do Hugging Face ou pelo link de uma entrada do catálogo (o ramo avançado no mesmo diálogo);
- importar um conjunto de arquivos de modelo de uma pasta no telefone;
- exportar um modelo baixado como **pacote offline** e importá-lo em outro aparelho, para que um modelo baixado uma vez migre sem novo download;
- apontar o aplicativo para outro índice de catálogo (a ação "change" ao lado da origem do catálogo) mantido por qualquer pessoa, por exemplo a sua comunidade.

O formato de importação e os requisitos de arquivos estão documentados em [modelos externos](../../external-models.md).

## Tasker e automação

O Anti-Vocale aceita um broadcast que o Tasker (ou qualquer aplicativo de automação) pode enviar para transcrever um arquivo sem tocar na interface:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

O resultado volta como um broadcast de resposta. O passo a passo completo com exemplos está no [guia do Tasker](../../TASKER_GUIDE.md).

## Monitoramento agendado de pastas

O Anti-Vocale pode monitorar pastas que você escolher e transcrever automaticamente novos arquivos de áudio: Configurações, Monitoramento agendado de pastas, Observar uma pasta. As varreduras rodam em uma programação que o sistema permite; economizadores de bateria agressivos podem atrasá-las, e a varredura manual Verificar agora sempre funciona. Cada novo arquivo de áudio é transcrito com o modelo selecionado no momento e vai para a aba Histórico como qualquer outra execução. No Android 11 ou mais novo, o seletor de pastas pode abrir locais de armazenamento compartilhado, como a pasta de mídia de um aplicativo de mensagens, o que torna possível a transcrição automática das mensagens de voz de uma conversa sem compartilhar cada uma.

## Offload remoto (experimental)

Se você mantém seu próprio servidor de transcrição, o Anti-Vocale pode delegar a ele transcrições longas ou pesadas: Configurações, Offload remoto (experimental). Qualquer servidor compatível com a OpenAI que fale a API /v1/audio/transcriptions serve (OmniVoice, o servidor do whisper.cpp, entre outros). O aviso dentro do aplicativo diz claramente: com o recurso ativado, o áudio das transcrições que você delegar é enviado apenas para o endereço de servidor que você inserir, onde quer que esse servidor esteja (sua rede doméstica ou qualquer lugar da internet); todo o resto é transcrito no aparelho, e o recurso vem desligado por padrão.

## Open Transcribe (experimental)

Aplicativos que falam o contrato aberto Open Transcribe, como o Forkgram (um cliente de Telegram), podem usar o Anti-Vocale como transcritor de mensagens de voz embutido: ative **Configurações > Automação > Open Transcribe API** (desligada por padrão) e escolha o Anti-Vocale nas configurações de transcrição offline do cliente. As mensagens de voz são transcritas com os modelos do próprio aplicativo e o áudio nunca sai do aparelho. Desligar a opção remove o aplicativo dos seletores dos clientes novamente.

## Configurações por aplicativo

Para cada aplicativo de onde você compartilha (WhatsApp, Telegram, ...) você pode configurar separadamente: se a ação de envio de volta aparece, se há cópia automática e o som da notificação. Aba Configurações, Configurações por aplicativo.

## Privacidade

- A transcrição é 100% no aparelho. Nenhum áudio, nenhum texto, nenhum metadado sai do seu telefone.
- O aplicativo não tem permissão de internet para transcrição; a rede é usada apenas quando você baixa um modelo explicitamente.
- Os logs ficam no seu aparelho e são seus: limpe-os quando quiser na aba Histórico.
- A versão Play inclui relatórios de falhas do Crashlytics (você pode ver e desativar nas configurações do Android); a versão F-Droid não tem nenhum.

## Solução de problemas

**A transcrição nunca termina / a notificação desaparece.**
Algumas marcas de telefone (Vivo, OPPO, alguns Xiaomi e Samsung) suspendem aplicativos em segundo plano de forma agressiva. Abra o Anti-Vocale uma vez e, se ele oferecer, conceda a isenção de bateria; ou encontre o aplicativo nas configurações de bateria e defina como Sem restrições. O aplicativo detecta essa situação e explica em uma notificação quando ela acontece.

**"Memória insuficiente" ou travamentos com modelos grandes.**
Os modelos listam o tamanho no cartão. Em telefones com 4 GB de RAM ou menos, use modelos abaixo de 500 MB. Se uma transcrição falhar com mensagem de falta de memória, tente um arquivo mais curto, um modelo menor ou feche outros aplicativos.

**A qualidade da transcrição está ruim.**
Tente um modelo especialista para o seu idioma (veja a tabela acima). Toque e segure a entrada ruim em Logs e use Report para nos enviar os detalhes (modelo, duração, tempos; o trecho da transcrição apenas se você escolher incluí-lo).

**O NNAPI faz travar.**
Se você ativou o provedor NNAPI em Configurações e o aplicativo agora trava, ele volta para a CPU automaticamente na próxima inicialização. O NNAPI depende muito do chipset do telefone; se travar repetidamente, deixe na CPU.

**As mensagens de voz automáticas pararam de chegar.**
Duas causas comuns. Se a pasta do WhatsApp foi apagada e recriada (uma reinstalação, uma ferramenta de limpeza), a permissão da pasta armazenada pode morrer: abra Configurações > Mensagens de voz e escolha a pasta novamente no cartão. Se o seu telefone suspende aplicativos em segundo plano de forma agressiva, mensagens que chegam com o aplicativo suspenso são perdidas até ele acordar: a isenção de bateria acima é a solução, e importa mais para este recurso do que para o compartilhamento manual.

## Perguntas frequentes

**Funciona sem internet?**
Sim. Depois de baixar um modelo, a transcrição funciona inteiramente offline.

**Quais aplicativos de mensagens são suportados?**
Qualquer aplicativo que compartilhe um arquivo de áudio. A ação de envio de volta atualmente mira um subconjunto de aplicativos (WhatsApp, Telegram e outros detectados automaticamente).

**Onde ficam minhas transcrições?**
Na aba Histórico e, opcionalmente, como arquivos .txt em uma pasta que você escolher. Nada é armazenado em nenhum outro lugar.

**Ele transcreve mensagens de voz automaticamente assim que chegam?**
Sim, no WhatsApp: ative Configurações > Mensagens de voz, conceda o acesso ao áudio e escolha uma única vez a pasta Voice Notes do WhatsApp. Novas mensagens são transcritas assim que chegam, e a linha pode trazer o nome do remetente. O monitoramento programado de pastas continua sendo o caminho genérico para a pasta de mídia de qualquer aplicativo (Android 11 ou mais novo), e o Telegram está no roteiro. Em telefones com gerenciadores de bateria agressivos, mensagens que chegam com o aplicativo suspenso podem ser perdidas: a isenção de bateria torna o modo automático confiável.

**Por que uma mensagem de voz não tem nome de remetente?**
O nome aparece apenas quando a notificação da mensagem chegou com o reconhecimento de remetente ligado e o tamanho informado corresponde ao áudio. Mensagens baixadas manualmente (download automático desligado) e mensagens cuja notificação nunca chegou não ganham nome, de propósito: um nome errado é pior que nenhum nome.

**Por que existem duas lojas (Play e F-Droid)?**
O mesmo aplicativo, os mesmos recursos. A F-Droid compila a partir do código-fonte sem componentes proprietários; a Play adiciona relatórios automáticos de falhas.

**É realmente privado?**
Sim. O código-fonte é aberto; você pode verificar que nenhum dado sai do aparelho. Veja a política de privacidade do repositório.

---

Encontrou um erro ou algo faltando? Abra uma issue no [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).

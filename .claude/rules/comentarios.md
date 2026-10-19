# Comentários e nomes

Vale para todo projeto meu, em qualquer linguagem.

## Quem escreve comentário

**Quem escreve o código.** Quem implementou é a única pessoa que sabe por que
aquilo está daquele jeito. Comentário escrito por quem só leu o código depois
vira adivinhação, e adivinhação em comentário é pior que silêncio.

O agente `docs` cuida de README, changelog e guia. Ele não entra no código.

## Como escrever

Escreva como um dev júnior explicando para um colega ao lado. Direto, curto,
sem enfeite.

**Comente o porquê, nunca o quê.** O código já diz o que faz.

```
// ruim, repete o que o código já mostra
// incrementa o tokenId
tokenId = nextTokenId++;

// bom, explica uma decisão que não é óbvia
// começa em 1 porque o 0 significa "não existe" na busca
int proximoId = 1;
```

## Regras de escrita

Essas são as que mais fazem comentário parecer escrito por máquina:

- **Nada de travessão nem hífen separando ideias.** Use ponto, vírgula ou dois
  pontos. Se a frase precisa de travessão, ela precisa virar duas frases.
- Sem "ou seja", "portanto", "dessa forma", "a fim de", "vale ressaltar",
  "note que", "é importante destacar". Vá direto ao ponto.
- Sem construção espelhada do tipo "não é X, é Y". Diga só o que é.
- Sem palavra difícil quando existe a fácil. "usa" no lugar de "utiliza",
  "mesmo" no lugar de "idêntico", "junta" no lugar de "concatena".
- Termo técnico só quando é o nome real da coisa. Chame mutex de mutex, mas
  não chame laço de "iteração sobre a coleção".
- Uma ou duas linhas. Se precisa de parágrafo, o código está complicado demais
  e o conserto é no código.
- Sem emoji, sem separador de ASCII, sem cabeçalho grande em cada função.

## Quando comentar

- A decisão não é óbvia e alguém ia perguntar "por que assim?"
- Tem pegadinha, limite ou caso estranho que o código não mostra
- Você tentou o jeito óbvio e não funcionou, então registre por quê

## Quando não comentar

- Código morto. Apague, o Git guarda.
- "Por enquanto", "temporário", "melhorar depois" sem dizer o que destrava.
  Isso vira linha no backlog, não comentário.
- Repetir o nome da função em forma de frase.

## Nomes

- Nomes em inglês no código, comentários e mensagens em português.
- Nome de variável diz o que ela guarda, não o tipo.
- Nome de função começa com verbo.
- Abreviação só quando é o termo do domínio.

## Mensagem de commit e de erro

Mesmas regras. Frase curta, voz ativa, sem travessão, sem adjetivo de
propaganda como "robusto", "poderoso" ou "elegante".

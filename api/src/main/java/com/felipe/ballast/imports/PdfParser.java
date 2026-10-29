package com.felipe.ballast.imports;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

// Extrai o texto da fatura com o PDFBox (só texto: sem OCR, sem renderização, sem executar
// JavaScript nem abrir anexo) e entrega a um leitor. PDF é entrada não confiável, então tudo é
// limitado: 5 MiB (a borda já corta), 30 páginas, 1 milhão de caracteres, 10 s, nada em disco e
// nenhum PDF com senha ou criptografia. O arquivo vive só em memória e o erro nunca leva a
// mensagem da biblioteca.
final class PdfParser {

  static final int MAX_BYTES = 5 << 20;
  static final int MAX_PAGES = 30;
  static final int MAX_CHARS = 1_000_000;
  static final long TIMEOUT_SECONDS = 10;

  // Leitores por emissor entram antes do genérico, que aceita qualquer texto.
  private static final List<BankStatementReader> READERS = List.of(new GenericStatementReader());

  // Uma thread por extração: o PDFBox não olha interrupção, então o timeout abandona a thread
  // (daemon, virtual) e os tetos acima limitam o quanto ela ainda gasta.
  // Extrações em andamento, contando as threads abandonadas no timeout: o permisso só volta quando
  // a thread termina de verdade. Cheio, a requisição é recusada em vez de empilhar trabalho.
  private static final Semaphore SLOTS = new Semaphore(3);
  // Teto do cache de fluxos decodificados do PDFBox (estoura com IOException, vira 422).
  private static final long STREAM_CACHE_BYTES = 64L << 20;

  private static final ExecutorService EXTRACTOR = Executors.newVirtualThreadPerTaskExecutor();

  static {
    // O PDFBox grava um cache de fontes em ~/.pdfbox.cache; no servidor isso iria para um home
    // que pode nem existir.
    System.setProperty("pdfbox.fontcache", System.getProperty("java.io.tmpdir"));
  }

  private PdfParser() {}

  static PdfFile parse(byte[] content, LocalDate today) {
    if (content == null || content.length == 0) {
      throw new PdfException("arquivo vazio");
    }
    if (content.length > MAX_BYTES) {
      throw new PdfException("PDF maior que 5 MiB");
    }
    if (!hasPdfHeader(content)) {
      throw new PdfException("o arquivo não parece um PDF");
    }
    String text = extract(content);
    if (text.isBlank()) {
      throw new PdfException(
          "o PDF não tem texto para ler (fatura escaneada ou em imagem não é aceita)");
    }
    PdfFile file = null;
    for (BankStatementReader reader : READERS) {
      if (reader.recognizes(text)) {
        file = reader.read(text, today);
        break;
      }
    }
    if (file == null || file.transactions().stream().noneMatch(t -> t.invalidReason() == null)) {
      throw new PdfException("nenhuma transação reconhecida nesta fatura");
    }
    return file;
  }

  // O marcador %PDF- pode vir depois de alguns bytes de lixo; a especificação deixa 1024.
  private static boolean hasPdfHeader(byte[] content) {
    int end = Math.min(content.length - 4, 1024);
    for (int i = 0; i < end; i++) {
      if (content[i] == '%'
          && content[i + 1] == 'P'
          && content[i + 2] == 'D'
          && content[i + 3] == 'F'
          && content[i + 4] == '-') {
        return true;
      }
    }
    return false;
  }

  private static String extract(byte[] content) {
    if (!SLOTS.tryAcquire()) {
      throw new PdfException("muitas leituras de PDF em andamento, tente de novo em instantes");
    }
    Future<String> task;
    try {
      task =
          EXTRACTOR.submit(
              () -> {
                try {
                  return extractText(content);
                } finally {
                  SLOTS.release();
                }
              });
    } catch (RuntimeException e) {
      SLOTS.release();
      throw e;
    }
    try {
      return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      task.cancel(true);
      throw new PdfException("o PDF demorou demais para ser lido");
    } catch (InterruptedException e) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new PdfException("leitura do PDF interrompida");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof PdfException pdf) {
        throw pdf;
      }
      throw new PdfException("não foi possível ler o PDF");
    }
  }

  private static String extractText(byte[] content) throws IOException {
    // Cache só em memória: o padrão do PDFBox 3 também é memória, mas o arquivo temporário seria
    // um jeito de o PDF ir parar em disco.
    try (PDDocument document =
        Loader.loadPDF(
            content,
            "",
            null,
            null,
            MemoryUsageSetting.setupMainMemoryOnly(STREAM_CACHE_BYTES).streamCache)) {
      if (document.isEncrypted()) {
        throw new PdfException("PDF protegido por senha ou criptografado não é aceito");
      }
      int pages = document.getNumberOfPages();
      if (pages > MAX_PAGES) {
        throw new PdfException("PDF com mais de " + MAX_PAGES + " páginas");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      CappedWriter text = new CappedWriter();
      for (int page = 1; page <= pages; page++) {
        if (Thread.currentThread().isInterrupted()) {
          throw new PdfException("leitura do PDF interrompida");
        }
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.writeText(document, text);
        text.write('\n');
      }
      return text.toString();
    } catch (InvalidPasswordException e) {
      throw new PdfException("PDF protegido por senha ou criptografado não é aceito");
    }
  }

  // O teto de caracteres vale enquanto o texto sai, não depois da página inteira.
  private static final class CappedWriter extends Writer {

    private final StringBuilder builder = new StringBuilder();

    @Override
    public void write(char[] buffer, int offset, int length) {
      if (builder.length() + length > MAX_CHARS) {
        throw new PdfException("PDF com texto demais para uma fatura");
      }
      builder.append(buffer, offset, length);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public String toString() {
      return builder.toString();
    }
  }
}

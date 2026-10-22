package com.felipe.ballast.fixedincome;

import java.time.LocalDate;
import java.util.List;

// De onde vêm os índices. Em produção é o Banco Central; nos testes, uma fonte em memória que pode
// fingir que está fora do ar. Lança exceção quando a fonte falha.
public interface IndexSource {

  List<IndexPoint> fetch(IndexSeries series, LocalDate from, LocalDate to);
}

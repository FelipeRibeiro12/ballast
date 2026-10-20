package com.felipe.ballast.recurrences;

import java.util.List;

// Parcelas na ordem da coluna "ordem": a primeira é a de ordem 1, a última recebe o resto do
// centavo (SalarySplit).
record Division(DivisionMode mode, List<DivisionPart> parts) {}

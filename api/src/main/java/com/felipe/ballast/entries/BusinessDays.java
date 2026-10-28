package com.felipe.ballast.entries;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

// Dia útil bancário nacional (ADR-060). Feriado estadual e municipal não entram. Carnaval,
// Sexta-feira
// Santa e Corpus Christi entram porque o banco não compensa boleto nesses dias, mesmo sem serem
// feriado de lei. Consciência Negra só vale a partir de 2024.
public final class BusinessDays {

  private BusinessDays() {}

  // O próprio dia quando já é útil, senão o primeiro útil seguinte.
  public static LocalDate nextOnOrAfter(LocalDate date) {
    LocalDate day = date;
    while (!isBusinessDay(day)) {
      day = day.plusDays(1);
    }
    return day;
  }

  public static boolean isBusinessDay(LocalDate date) {
    DayOfWeek week = date.getDayOfWeek();
    if (week == DayOfWeek.SATURDAY || week == DayOfWeek.SUNDAY) {
      return false;
    }
    return !holidays(date.getYear()).contains(date);
  }

  static Set<LocalDate> holidays(int year) {
    Set<LocalDate> days = new HashSet<>();
    days.add(LocalDate.of(year, 1, 1));
    days.add(LocalDate.of(year, 4, 21));
    days.add(LocalDate.of(year, 5, 1));
    days.add(LocalDate.of(year, 9, 7));
    days.add(LocalDate.of(year, 10, 12));
    days.add(LocalDate.of(year, 11, 2));
    days.add(LocalDate.of(year, 11, 15));
    days.add(LocalDate.of(year, 12, 25));
    if (year >= 2024) {
      days.add(LocalDate.of(year, 11, 20));
    }
    LocalDate easter = easter(year);
    days.add(easter.minusDays(48));
    days.add(easter.minusDays(47));
    days.add(easter.minusDays(2));
    days.add(easter.plusDays(60));
    return days;
  }

  // Algoritmo anônimo gregoriano (Meeus/Jones/Butcher).
  static LocalDate easter(int year) {
    int a = year % 19;
    int b = year / 100;
    int c = year % 100;
    int d = b / 4;
    int e = b % 4;
    int f = (b + 8) / 25;
    int g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30;
    int i = c / 4;
    int k = c % 4;
    int l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int month = (h + l - 7 * m + 114) / 31;
    int day = (h + l - 7 * m + 114) % 31 + 1;
    return LocalDate.of(year, month, day);
  }
}

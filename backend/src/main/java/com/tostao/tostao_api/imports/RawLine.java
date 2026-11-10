package com.tostao.tostao_api.imports;

import java.time.LocalDate;
import java.util.List;

/**
 * Um movimento como o leitor o encontrou, antes da normalização. {@code date} e {@code cents} são nulos quando não deu
 * para lê-los (e então {@code problems} diz por quê). {@code identifier} é o identificador do banco, se houver.
 */
record RawLine(int line, LocalDate date, String description, Long cents, String identifier, List<String> problems) {
}

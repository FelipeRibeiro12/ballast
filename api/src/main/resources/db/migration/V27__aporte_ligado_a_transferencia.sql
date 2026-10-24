-- ADR-057. O aporte de meta pode apontar para a transferência que moveu o dinheiro, para o mesmo
-- dinheiro não ser registrado duas vezes. O vínculo é opcional e só informa; o saldo da meta
-- continua sendo a soma dos aportes.
-- ON DELETE NO ACTION: apagar a transferência vinculada é recusado pelo banco, e a aplicação
-- responde 409. Apagar o aporte (ou a meta, no cascade) solta a transferência.
ALTER TABLE transferencia ADD CONSTRAINT transferencia_id_usuario_unico UNIQUE (id, usuario_id);

ALTER TABLE aporte_meta ADD COLUMN transferencia_id uuid;
-- FK composta: a transferência é do mesmo usuário do aporte. É MATCH SIMPLE, então o aporte sem
-- vínculo (transferencia_id nulo) não é checado.
ALTER TABLE aporte_meta ADD CONSTRAINT aporte_meta_transferencia_mesmo_usuario
    FOREIGN KEY (transferencia_id, usuario_id)
    REFERENCES transferencia (id, usuario_id) ON DELETE NO ACTION;
-- Uma transferência liga no máximo um aporte, senão o mesmo dinheiro conta duas vezes. Parcial
-- para os aportes sem vínculo não entrarem. Também atende a checagem da FK ao apagar a
-- transferência.
CREATE UNIQUE INDEX aporte_meta_transferencia_unica
    ON aporte_meta (transferencia_id) WHERE transferencia_id IS NOT NULL;

-- Para desfazer (o Flyway Community não tem down; rode à mão). Perde o vínculo dos aportes.
-- DROP INDEX aporte_meta_transferencia_unica;
-- ALTER TABLE aporte_meta DROP CONSTRAINT aporte_meta_transferencia_mesmo_usuario;
-- ALTER TABLE aporte_meta DROP COLUMN transferencia_id;
-- ALTER TABLE transferencia DROP CONSTRAINT transferencia_id_usuario_unico;

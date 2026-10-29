-- ADR-068. A visão padrão passa a nascer no cadastro, e o GET deixa de escrever. Quem já existia ganha a
-- sua aqui, uma vez. Quem já tem alguma visão não recebe outra.
INSERT INTO visao_saldo (id, usuario_id, titulo, tipos)
SELECT gen_random_uuid(), u.id, 'Total sem cartão',
       ARRAY['corrente', 'poupanca', 'dinheiro', 'beneficio', 'cripto', 'investimento',
             'cofrinho']::text[]
FROM usuario u
WHERE NOT EXISTS (SELECT 1 FROM visao_saldo v WHERE v.usuario_id = u.id);

-- Desfazer: não há. A visão padrão é uma linha como as outras e o usuário pode editá-la.

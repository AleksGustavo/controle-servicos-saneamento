package br.controleservicos.app.core.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import br.controleservicos.app.core.database.AppDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resumo de um arquivo de backup validado. */
data class InfoBackup(val versaoEsquema: Int, val servicos: Int, val funcionarios: Int)

class BackupInvalidoException(msg: String) : Exception(msg)

/**
 * Backup local portável = cópia íntegra do banco SQLite (versionado por `PRAGMA user_version`).
 *
 * - Geração: dentro de uma transação de escrita (nenhum outro gravador), copia o arquivo principal
 *   e o WAL; abre a cópia, consolida o WAL num único arquivo e roda `integrity_check`.
 * - Restauração: valida o arquivo (cabeçalho SQLite, integridade, tabelas, versão ≤ atual),
 *   guarda uma cópia de segurança do banco atual e só então substitui. Versões antigas
 *   (ex.: v1) são migradas pelo Room na próxima abertura, sem perda.
 */
object BackupBanco {
    const val MIME = "application/octet-stream"
    private const val PASTA_SEGURANCA = "backups_seguranca"
    private const val MAX_COPIAS_SEGURANCA = 3
    private val CABECALHO = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun nomeArquivo(agoraMs: Long, zona: ZoneId): String =
        "backup_controle_servicos_${FMT.format(Instant.ofEpochMilli(agoraMs).atZone(zona))}.db"

    private fun irmaos(f: File) = listOf(File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal"))

    /** Gera em [destino] uma cópia consistente e autocontida do banco aberto [db]. */
    fun gerarCopia(db: AppDatabase, arquivoBanco: File, destino: File): InfoBackup {
        destino.delete(); irmaos(destino).forEach { it.delete() }
        db.runInTransaction {
            arquivoBanco.copyTo(destino, overwrite = true)
            val wal = File(arquivoBanco.path + "-wal")
            if (wal.exists() && wal.length() > 0) wal.copyTo(File(destino.path + "-wal"), overwrite = true)
        }
        // Consolida o WAL copiado no arquivo principal e deixa o arquivo sem dependências.
        SQLiteDatabase.openDatabase(destino.path, null, SQLiteDatabase.OPEN_READWRITE).use { c ->
            c.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            c.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        }
        irmaos(destino).forEach { it.delete() }
        return validar(destino)
    }

    /** Valida um arquivo candidato a backup. Lança [BackupInvalidoException] com motivo legível. */
    fun validar(arquivo: File): InfoBackup {
        if (!arquivo.exists() || arquivo.length() < 512) throw BackupInvalidoException("Arquivo vazio ou muito pequeno para ser um backup.")
        val cab = ByteArray(CABECALHO.size)
        arquivo.inputStream().use { if (it.read(cab) != cab.size) throw BackupInvalidoException("Arquivo ilegível.") }
        if (!cab.contentEquals(CABECALHO)) throw BackupInvalidoException("O arquivo não é um backup deste aplicativo (formato desconhecido).")
        val c = try {
            SQLiteDatabase.openDatabase(arquivo.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupInvalidoException("Não foi possível abrir o backup: ${e.message}")
        }
        c.use { db ->
            val integridade = db.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (integridade != "ok") throw BackupInvalidoException("Backup corrompido (verificação de integridade falhou).")
            val tabelas = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cur ->
                buildSet { while (cur.moveToNext()) add(cur.getString(0)) }
            }
            val obrigatorias = setOf("servico", "tipo_servico", "bairro", "municipio", "evento_servico", "room_master_table")
            if (!tabelas.containsAll(obrigatorias)) throw BackupInvalidoException("O arquivo não contém as tabelas deste aplicativo.")
            val versao = db.version
            if (versao < 1) throw BackupInvalidoException("Versão de esquema inválida ($versao).")
            if (versao > AppDatabase.VERSAO) {
                throw BackupInvalidoException("Backup criado por uma versão mais nova do aplicativo (esquema $versao). Atualize o app antes de restaurar.")
            }
            fun contar(t: String) = if (t in tabelas) db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getInt(0) } else 0
            return InfoBackup(versao, contar("servico"), contar("funcionario"))
        }
    }

    /**
     * Substitui o banco atual pelo [arquivoValidado]. Antes, grava uma cópia de segurança do banco
     * atual em `files/backups_seguranca`. Fecha [db]: o processo deve ser reiniciado em seguida.
     * Retorna o arquivo da cópia de segurança.
     */
    fun restaurar(context: Context, db: AppDatabase, arquivoValidado: File, agoraMs: Long, nomeBanco: String = AppDatabase.NOME_ARQUIVO): File {
        validar(arquivoValidado)
        val atual = context.getDatabasePath(nomeBanco)
        val pasta = File(context.filesDir, PASTA_SEGURANCA).apply { mkdirs() }
        val seguranca = File(pasta, "antes_restauracao_${FMT.format(Instant.ofEpochMilli(agoraMs).atZone(ZoneId.of("UTC")))}.db")
        gerarCopia(db, atual, seguranca)
        db.close()
        val temp = File(atual.path + ".restaurando")
        arquivoValidado.copyTo(temp, overwrite = true)
        validar(temp)
        irmaos(atual).forEach { it.delete() }
        if (!temp.renameTo(atual)) {
            temp.copyTo(atual, overwrite = true)
            temp.delete()
        }
        pasta.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_COPIAS_SEGURANCA)?.forEach { it.delete() }
        return seguranca
    }

    /** Reinicia o app (necessário após restaurar, pois o banco anterior foi fechado). */
    fun reiniciarApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }
}

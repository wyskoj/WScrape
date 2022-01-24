import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Makes repeated calls over an SSH connection to execute the unix `w` command. The results are parsed and stored into
 * a SQL server. The server must have this schema:
 *
 * ```sql
 * create table LoginEntry
 * (
 *     record_time timestamp    not null,
 *     user        varchar(16)  not null,
 *     tty         varchar(16)  not null,
 *     `from`      varchar(32)  not null,
 *     `login@`    varchar(16)  not null,
 *     idle        varchar(16)  not null,
 *     jcpu        varchar(16)  not null,
 *     pcpu        varchar(16)  not null,
 *     what        varchar(256) not null,
 *     primary key (user, record_time, tty)
 * );
 * ```
 *
 * When a `WScrape` object is instantiated, a thread is opened that will keep the JVM alive. To begin scraping, call
 * [start]. To stop scraping, call [stop]. When you are finished performing scrapes, you must call [dispose], otherwise
 * the thread will be kept alive.
 */
class WScrape(
    /** The JDBC URL of the SQL server. */
    private val sqlUrl: String,
    /** The SSH host. */
    private val sshHost: String,
    /** The amount of time to wait in between captures, expressed in milliseconds. */
    private val captureInterval: Long,
    /** A file that conforms to [Login] that provides credentials for the SQL server. */
    private val sqlLogin: File,
    /** A file that conforms to [Login] that provides credentials for the SSH. */
    private val sshLogin: File,
    /** An optional callback called on each capture. */
    private val onCapture: (rows: List<LoginEntry>) -> Unit = {}
) {

    private val sql: Connection = with(Json.decodeFromString<Login>(sqlLogin.readText())) {
        DriverManager.getConnection(sqlUrl, user, pass)
    }

    private val ssh = with(Json.decodeFromString<Login>(sshLogin.readText())) {
        JSch().getSession(user, sshHost, 22).apply {
            setConfig(
                Properties().apply {
                    put("StrictHostKeyChecking", "no")
                }
            )
            setPassword(pass)
            connect()
        }
    }

    private val job = CoroutineScope(Default).launch(start = CoroutineStart.LAZY) {
        while (true) {
            with(ssh.openChannel("exec")) {
                this as ChannelExec
                setCommand("w")
                connect()
                withContext(Dispatchers.IO) {
                    with(String(inputStream.readAllBytes()).parseW()) {
                        forEach { it.save(sql) }
                        onCapture.invoke(this)
                    }
                    disconnect()
                }
            }
            delay(captureInterval)
        }
    }

    /** Start scraping. */
    fun start() {
        job.start()
    }

    /** Stop scraping. */
    fun stop() {
        job.cancel()
    }

    /** Disconnects from SQL and SSH. */
    fun dispose() {
        sql.abort(Runnable::run)
        ssh.disconnect()
    }
}

/**
 * Given a `w` response, parses the contents and returns a list of [LoginEntry]s representing that data.
 */
fun String.parseW(): List<LoginEntry> {
    val rows = split("\n")
    val time = Regex(""".*(\d{2}:\d{2}:\d{2}).*""").find(rows[0])?.groupValues?.get(1)
    val date = DateTimeFormatter.ISO_DATE.format(LocalDateTime.now())
    return rows.subList(2, rows.size).mapNotNull { row ->
        Regex("""(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(.+)""").find(row)?.groupValues?.let {
            LoginEntry(
                "$date $time",
                it[1],
                it[2],
                it[3],
                it[4],
                it[5],
                it[6],
                it[7],
                it[8],
            )
        }
    }
}

/**
 * Represents a row from the result of `w`.
 */
@Suppress("SpellCheckingInspection")
data class LoginEntry(
    val record_time: String,
    val user: String,
    val tty: String,
    val from: String,
    val `login@`: String,
    val idle: String,
    val jcpu: String,
    val pcpu: String,
    val what: String
) {
    fun save(conn: Connection) =
        conn.prepareStatement("INSERT INTO LoginEntry (record_time, user, tty, `from`, `login@`, idle, jcpu, pcpu, what) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .apply {
                setString(1, record_time)
                setString(2, user)
                setString(3, tty)
                setString(4, from)
                setString(5, `login@`)
                setString(6, idle)
                setString(7, jcpu)
                setString(8, pcpu)
                setString(9, what)
            }.execute()

}

/** Represents local credentials. Example of a file that follows this schema:
 * ```json
 * {"user": "myusername", "pass": "mypassword"}
 * ```
 */
@Serializable
data class Login(
    /** The username. */
    val user: String,
    /** The password. */
    val pass: String
)
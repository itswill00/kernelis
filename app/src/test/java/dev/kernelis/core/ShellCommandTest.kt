package dev.kernelis.core

import com.google.common.truth.Truth.assertThat
import dev.kernelis.core.root.ShellCommand
import dev.kernelis.core.root.ShellFailure
import dev.kernelis.core.root.ShellResult
import org.junit.Test

/**
 * The command surface is the boundary between "user data" and "shell". These tests pin
 * down the escaping rules, because a regression here is a command-injection bug.
 */
class ShellCommandTest {

    @Test
    fun `plain tokens are not quoted`() {
        assertThat(ShellCommand.quote("schedutil")).isEqualTo("schedutil")
        assertThat(ShellCommand.quote("1804000")).isEqualTo("1804000")
        assertThat(ShellCommand.quote("/sys/devices/system/cpu")).isEqualTo("/sys/devices/system/cpu")
    }

    @Test
    fun `empty string becomes empty single quotes`() {
        assertThat(ShellCommand.quote("")).isEqualTo("''")
    }

    @Test
    fun `spaces are quoted`() {
        assertThat(ShellCommand.quote("a b")).isEqualTo("'a b'")
    }

    @Test
    fun `single quotes are escaped by closing and reopening`() {
        // The canonical POSIX escape: ' -> '\''
        assertThat(ShellCommand.quote("it's")).isEqualTo("'it'\\''s'")
    }

    @Test
    fun `command injection attempt cannot break out of its argument`() {
        val hostile = "1; rm -rf /"
        val rendered = ShellCommand("printf", listOf(hostile)).render()
        // The semicolon must stay inside the quotes; the shell sees a single argument.
        assertThat(rendered).isEqualTo("printf '1; rm -rf /'")
        // Strip the quoted value and confirm no unquoted metacharacter remains.
        val unquoted = rendered.removePrefix("printf '").removeSuffix("'")
        assertThat(unquoted).isEqualTo(hostile)
    }

    @Test
    fun `backticks and dollar signs are neutralised`() {
        val rendered = ShellCommand("printf", listOf("\$(reboot)`reboot`")).render()
        assertThat(rendered).startsWith("printf '")
        assertThat(rendered).endsWith("'")
        assertThat(rendered.contains("' ")).isFalse()
    }

    @Test
    fun `newline inside a value is quoted rather than producing a new command`() {
        val rendered = ShellCommand("printf", listOf("1\nreboot")).render()
        assertThat(rendered).startsWith("printf '")
        assertThat(rendered.lines().size).isEqualTo(2)
    }
}

class ShellResultTest {

    private fun result(exit: Int, out: String = "", err: String = "", failure: ShellFailure? = null) =
        ShellResult("cmd", out, err, exit, 0, failure)

    @Test
    fun `not executed is never success`() {
        val r = result(ShellResult.EXIT_NOT_EXECUTED, failure = ShellFailure.NoRoot)
        assertThat(r.isSuccess).isFalse()
    }

    @Test
    fun `zero exit with failure marker is not success`() {
        val r = result(0, failure = ShellFailure.Timeout(1000))
        assertThat(r.isSuccess).isFalse()
    }

    @Test
    fun `zero exit without failure is success`() {
        assertThat(result(0).isSuccess).isTrue()
    }

    @Test
    fun `combined output joins stdout then stderr`() {
        assertThat(result(0, "out", "err").combinedOutput).isEqualTo("out\nerr")
        assertThat(result(0, "out", "").combinedOutput).isEqualTo("out")
        assertThat(result(0, "", "err").combinedOutput).isEqualTo("err")
    }
}

package nebula.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Makes a local copy of a public GitHub repository in the projects folder.
 *
 * No sign-in is needed for a public repository, so none is asked for: the repository is downloaded as the zip that GitHub
 * serves for every public repository and then opened like any other zip ([ProjectImport.importZip]). A private repository
 * answers "not found" without a sign-in, which is reported as such. Android-free; the network call is a parameter so tests can fake it.
 */
object GitHubImport {
    /** [refs] are the branch / tag names to try, in order (empty = the default branch). */
    class Repo(val owner: String, val name: String, val refs: List<String>)

    private val ownerRe = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val nameRe = Regex("[A-Za-z0-9._-]{1,100}")
    private val hostRe = Regex("^(?:https?://)?(?:www\\.)?github\\.com[/:]", RegexOption.IGNORE_CASE)

    /**
     * Understands `https://github.com/owner/repo` (also with `.git`, a trailing slash, `?tab=...`, `#readme`, or `/tree/<branch>`),
     * `git@github.com:owner/repo.git`, `github.com/owner/repo` and plain `owner/repo`.
     */
    fun parse(raw: String): Repo {
        var s = raw.trim().substringBefore('#').substringBefore('?')
        if (s.isEmpty()) throw ProjectImport.ImportException("type the address of a GitHub repository")
        s = s.removePrefix("ssh://git@github.com/")
        s = hostRe.replace(s, "").replace(Regex("^git@github\\.com:", RegexOption.IGNORE_CASE), "")
        if (s.contains("://") || s.any { it.isWhitespace() }) throw ProjectImport.ImportException("that is not a GitHub address (use https://github.com/owner/repo)")
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) throw ProjectImport.ImportException("the address needs an owner and a repository, like https://github.com/owner/repo")
        val owner = parts[0]; val name = parts[1].removeSuffix(".git")
        if (!ownerRe.matches(owner) || !nameRe.matches(name) || name == "." || name == "..") throw ProjectImport.ImportException("that is not a GitHub repository address")
        // .../tree/<branch or tag>[/folder]: the name may contain slashes and a folder may follow it, so try the shortest reading first
        val refs = ArrayList<String>()
        if (parts.size >= 4 && parts[2] == "tree") {
            val rest = parts.drop(3)
            if (rest.any { it == ".." || it == "." }) throw ProjectImport.ImportException("that branch name is not valid")
            for (n in 1..rest.size) refs.add(rest.take(n).joinToString("/"))
        }
        return Repo(owner, name, refs)
    }

    private fun enc(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** The zip of one branch / tag (or "HEAD" = the default branch). */
    fun archiveUrl(repo: Repo, ref: String) = "https://github.com/${repo.owner}/${repo.name}/archive/${enc(ref)}.zip"

    /** Opens [url]; null when GitHub has no public page for it (a repository that does not exist looks the same as a private one without a sign-in). */
    fun httpOpen(url: String): InputStream? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Nebula")
        val code = c.responseCode
        if (code == 404 || code == 403) { c.disconnect(); return null }          // not found, private, or not allowed: all mean "no public copy"
        if (code !in 200..299) { c.disconnect(); throw IOException(if (code == 429) "GitHub is limiting requests right now, try again in a few minutes" else "GitHub answered with error $code") }
        return c.inputStream
    }

    /**
     * Downloads the repository described by [input] and opens it as a project inside [root]. The project is named after the repository
     * unless it has its own title; [title] and [packageId] (when not blank) are the user's choice and win over both.
     */
    fun import(input: String, root: File, scratch: File, open: (String) -> InputStream? = ::httpOpen, title: String? = null, packageId: String? = null): Project {
        val repo = parse(input)
        if (!packageId.isNullOrBlank() && !ProjectImport.isPackageId(packageId.trim())) throw ProjectImport.ImportException("the package id must look like com.example.mygame (lowercase letters, digits and underscores, with at least one dot)")
        val candidates = if (repo.refs.isEmpty()) listOf("HEAD") else repo.refs
        for (ref in candidates) {
            val stream = try { open(archiveUrl(repo, ref)) }
            catch (e: java.net.UnknownHostException) { throw ProjectImport.ImportException("cannot reach GitHub, check the internet connection") }
            catch (e: java.net.SocketTimeoutException) { throw ProjectImport.ImportException("GitHub took too long to answer, try again") }
            catch (e: java.net.ConnectException) { throw ProjectImport.ImportException("cannot reach GitHub, check the internet connection") }
            if (stream != null) return stream.use { ProjectImport.importZip(it, root, repo.name, scratch, title, packageId) }
        }
        throw ProjectImport.ImportException(
            if (repo.refs.isEmpty()) "'${repo.owner}/${repo.name}' was not found. Only public repositories can be imported, so check the spelling and that it is not private."
            else "'${repo.owner}/${repo.name}' has no branch or tag '${repo.refs.last()}' (or it is private)")
    }
}

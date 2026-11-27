using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Casana;

// Stay signed in (1.7, 27 Nov 2026): Casana's login kept on this computer so the app can sign in again by itself.
// Apper's sign-in lasts 8 hours and its "Remember me" box does nothing, so people were asked to sign in again and
// again. The email and password are encrypted with Windows' own data protection for this Windows user (DPAPI): only
// this Windows account on this computer can read them back. Sign out in Casana deletes the file.
internal static class LoginStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("CasaAuditGroup.Casana.login.v1");

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Casana", "login.bin");

    public static bool Save(string? email, string? password)
    {
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password)) return false;
        try
        {
            var plain = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, string> { ["email"] = email.Trim(), ["password"] = password });
            var sealedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllBytes(FilePath, sealedBytes);
            return true;
        }
        catch
        {
            Clear();
            return false;
        }
    }

    // (email, password), or (null, null) when nothing is saved or it cannot be read.
    public static (string? Email, string? Password) Read()
    {
        try
        {
            if (!File.Exists(FilePath)) return (null, null);
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(FilePath), Entropy, DataProtectionScope.CurrentUser);
            var data = JsonSerializer.Deserialize<Dictionary<string, string>>(plain);
            if (data == null || !data.TryGetValue("email", out var e) || !data.TryGetValue("password", out var p)) return (null, null);
            return (e, p);
        }
        catch
        {
            Clear();
            return (null, null);
        }
    }

    public static void Clear()
    {
        try
        {
            if (File.Exists(FilePath)) File.Delete(FilePath);
        }
        catch
        {
        }
    }
}

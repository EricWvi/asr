package config

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// write saves a config file and clears the secret environment variables,
// which `task` loads from .env, so tests only see their own values.
func write(t *testing.T, content string) string {
	t.Helper()
	for _, env := range []string{ClientSecretEnv, DashScopeAPIKeyEnv, TidyAPIKeyEnv} {
		t.Setenv(env, "")
	}
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

const validOIDC = `
public_url: https://asr.test
oidc:
  issuer: https://auth.test
  client_id: asr
  client_secret: secret
`

func TestLoadRequiresAuthentication(t *testing.T) {
	t.Setenv(ClientSecretEnv, "")
	_, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err == nil || !strings.Contains(err.Error(), "public_url") {
		t.Fatalf("err = %v, want public_url error", err)
	}
}

func TestLoadOIDC(t *testing.T) {
	cfg, err := Load(write(t, "listen: \":9000\"\n"+validOIDC))
	if err != nil {
		t.Fatal(err)
	}
	want := Default()
	want.Listen = ":9000"
	want.PublicURL = "https://asr.test"
	want.OIDC = OIDC{Issuer: "https://auth.test", ClientID: "asr", ClientSecret: "secret"}
	if !reflect.DeepEqual(cfg, want) {
		t.Fatalf("got %+v, want %+v", cfg, want)
	}
}

func TestLoadSecretsFromEnv(t *testing.T) {
	path := write(t, strings.Replace(validOIDC, "  client_secret: secret\n", "", 1)+`
dashscope:
  host: llm-x.cn-beijing.maas.aliyuncs.com
  api_key: from-file
`)
	t.Setenv(ClientSecretEnv, "oidc-env")
	t.Setenv(DashScopeAPIKeyEnv, "dashscope-env")
	t.Setenv(TidyAPIKeyEnv, "tidy-env")
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.OIDC.ClientSecret != "oidc-env" || cfg.DashScope.APIKey != "dashscope-env" || cfg.Tidy.APIKey != "tidy-env" {
		t.Fatalf("secrets = %q %q %q", cfg.OIDC.ClientSecret, cfg.DashScope.APIKey, cfg.Tidy.APIKey)
	}
	if cfg.DashScope.Host != "llm-x.cn-beijing.maas.aliyuncs.com" || cfg.DashScope.Model != "qwen-audio-3.1-asr-flash-message" {
		t.Fatalf("dashscope = %+v", cfg.DashScope)
	}
}

func TestLoadDevUserNeedsNoOIDC(t *testing.T) {
	if _, err := Load(write(t, "dev_user: eric\n")); err != nil {
		t.Fatal(err)
	}
}

func TestLoadRejectsUnknownKeys(t *testing.T) {
	// A misplaced dev_user under oidc must not silently fall back to OIDC.
	_, err := Load(write(t, validOIDC+"  dev_user: eric\n"))
	if err == nil || !strings.Contains(err.Error(), "dev_user") {
		t.Fatalf("err = %v, want unknown dev_user error", err)
	}
}

func TestLoadEmptyFile(t *testing.T) {
	_, err := Load(write(t, ""))
	if err == nil || !strings.Contains(err.Error(), "public_url") {
		t.Fatalf("err = %v, want public_url error", err)
	}
}

func TestLoadRejectsInvalidValues(t *testing.T) {
	for _, content := range []string{
		"data_dir: \"\"\ndev_user: eric\n",
		strings.Replace(validOIDC, "https://asr.test", "https://asr.test/sub", 1),
		strings.Replace(validOIDC, "https://asr.test", "asr.test", 1),
		strings.Replace(validOIDC, "  issuer: https://auth.test\n", "", 1),
	} {
		if _, err := Load(write(t, content)); err == nil {
			t.Errorf("expected error for:\n%s", content)
		}
	}
}

func TestLoadTidyExtraBody(t *testing.T) {
	cfg, err := Load(write(t, `dev_user: eric
tidy:
  base_url: https://api.deepseek.com
  model: deepseek-flash
  extra_body:
    thinking:
      type: disabled
    temperature: 0.3
`))
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"thinking": map[string]any{"type": "disabled"}, "temperature": 0.3}
	if !reflect.DeepEqual(cfg.Tidy.ExtraBody, want) {
		t.Fatalf("extra_body = %#v", cfg.Tidy.ExtraBody)
	}
}

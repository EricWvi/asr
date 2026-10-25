// Package config loads the YAML configuration file.
package config

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/url"
	"os"

	"gopkg.in/yaml.v3"
)

// Environment variables that override secrets so they can stay out of the
// config file.
const (
	ClientSecretEnv    = "ASR_OIDC_CLIENT_SECRET"
	DashScopeAPIKeyEnv = "ASR_DASHSCOPE_API_KEY"
	TidyAPIKeyEnv      = "ASR_TIDY_API_KEY"
)

// Config is the application configuration.
type Config struct {
	// Listen is the HTTP listen address, e.g. ":36750".
	Listen string `yaml:"listen"`
	// DataDir holds the SQLite database and uploaded files.
	DataDir string `yaml:"data_dir"`
	// PublicURL is the origin browsers use to reach the site, e.g.
	// "https://asr.example.com". It forms the OIDC redirect URI.
	PublicURL string `yaml:"public_url"`
	OIDC      OIDC   `yaml:"oidc"`
	// DevUser, when set, signs every request in as this local user and
	// disables OIDC. For local development only.
	DevUser   string    `yaml:"dev_user"`
	DashScope DashScope `yaml:"dashscope"`
	Tidy      Tidy      `yaml:"tidy"`
}

// OIDC configures the identity provider (Authelia).
type OIDC struct {
	Issuer       string `yaml:"issuer"`
	ClientID     string `yaml:"client_id"`
	ClientSecret string `yaml:"client_secret"`
}

// DashScope configures speech recognition on Alibaba Cloud Model Studio.
type DashScope struct {
	// Host is the workspace endpoint host, which already names the
	// workspace and region, e.g. "llm-xxx.cn-beijing.maas.aliyuncs.com".
	Host   string `yaml:"host"`
	Model  string `yaml:"model"`
	APIKey string `yaml:"api_key"`
}

// Tidy configures the chat model that turns transcripts into written
// Markdown. Any OpenAI-compatible chat completions API works.
type Tidy struct {
	// BaseURL is the API root, e.g. "https://api.deepseek.com"; requests go
	// to <base_url>/chat/completions.
	BaseURL string `yaml:"base_url"`
	Model   string `yaml:"model"`
	APIKey  string `yaml:"api_key"`
	// ExtraBody is merged into every request body as it is, for vendor
	// parameters such as DeepSeek's thinking switch. The standard fields
	// (model, messages, stream) win over it.
	ExtraBody map[string]any `yaml:"extra_body"`
}

// Default returns the configuration used when no file is present.
func Default() Config {
	return Config{
		Listen:    ":36750",
		DataDir:   "./data",
		DashScope: DashScope{Model: "qwen-audio-3.1-asr-flash-message"},
	}
}

// Load reads the YAML file at path on top of the defaults.
// A missing file is not an error; the defaults are used. Unknown keys are
// rejected so a misplaced setting such as dev_user cannot be ignored.
func Load(path string) (Config, error) {
	cfg := Default()
	raw, err := os.ReadFile(path)
	switch {
	case errors.Is(err, fs.ErrNotExist):
	case err != nil:
		return cfg, fmt.Errorf("read config: %w", err)
	default:
		dec := yaml.NewDecoder(bytes.NewReader(raw))
		dec.KnownFields(true)
		if err := dec.Decode(&cfg); err != nil && !errors.Is(err, io.EOF) {
			return cfg, fmt.Errorf("parse config %s: %w", path, err)
		}
	}
	for env, field := range map[string]*string{
		ClientSecretEnv:    &cfg.OIDC.ClientSecret,
		DashScopeAPIKeyEnv: &cfg.DashScope.APIKey,
		TidyAPIKeyEnv:      &cfg.Tidy.APIKey,
	} {
		if v := os.Getenv(env); v != "" {
			*field = v
		}
	}
	return cfg, cfg.validate()
}

func (c Config) validate() error {
	if c.Listen == "" {
		return errors.New("config: listen must not be empty")
	}
	if c.DataDir == "" {
		return errors.New("config: data_dir must not be empty")
	}
	if c.DevUser != "" {
		return nil
	}
	u, err := url.Parse(c.PublicURL)
	if c.PublicURL == "" || err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" ||
		(u.Path != "" && u.Path != "/") {
		return errors.New("config: public_url must be an origin such as https://asr.example.com")
	}
	if c.OIDC.Issuer == "" || c.OIDC.ClientID == "" || c.OIDC.ClientSecret == "" {
		return fmt.Errorf("config: oidc.issuer, oidc.client_id and oidc.client_secret (or %s) are required unless dev_user is set", ClientSecretEnv)
	}
	return nil
}

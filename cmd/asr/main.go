// Command asr serves the ASR service and its API from a single process.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"asr"
	"asr/internal/auth"
	"asr/internal/config"
	"asr/internal/dashscope"
	"asr/internal/server"
	"asr/internal/store"
	"asr/internal/tidy"
)

// Set at build time with -ldflags "-X main.version=... -X main.commit=...".
var (
	version = "dev"
	commit  = "unknown"
)

func main() {
	configPath := flag.String("config", "config.yaml", "path to the YAML config file")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("asr %s (%s)\n", version, commit)
		return
	}
	if err := run(*configPath); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.DataDir)
	if err != nil {
		return err
	}
	defer st.Close()

	if cfg.DevUser != "" {
		slog.Warn("dev_user is set: OIDC is disabled and every request is signed in as this user", "user", cfg.DevUser)
	}
	authn := auth.New(st, auth.Config{
		PublicURL:    cfg.PublicURL,
		Issuer:       cfg.OIDC.Issuer,
		ClientID:     cfg.OIDC.ClientID,
		ClientSecret: cfg.OIDC.ClientSecret,
		DevUser:      cfg.DevUser,
	})
	opts := server.Options{Version: version}
	if cfg.DashScope.Host != "" && cfg.DashScope.APIKey != "" {
		opts.ASR = dashscope.New(cfg.DashScope.Host, cfg.DashScope.Model, cfg.DashScope.APIKey)
	} else {
		slog.Warn("dashscope host or api key is not set: speech recognition is off")
	}
	if cfg.Tidy.BaseURL != "" && cfg.Tidy.Model != "" && cfg.Tidy.APIKey != "" {
		opts.Tidy = tidy.New(cfg.Tidy.BaseURL, cfg.Tidy.Model, cfg.Tidy.APIKey, cfg.Tidy.ExtraBody)
	} else {
		slog.Warn("tidy base_url, model or api key is not set: tidying is off")
	}
	handler, err := server.New(st, authn, asr.Frontend(), opts)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go purgeTrash(ctx, st)

	errc := make(chan error, 1)
	go func() {
		slog.Info("listening", "addr", cfg.Listen, "data_dir", cfg.DataDir, "version", version)
		errc <- srv.ListenAndServe()
	}()

	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

// purgeTrash removes documents that have been in the trash longer than
// store.TrashRetention and then images no document refers to, once at
// startup and then hourly.
func purgeTrash(ctx context.Context, st *store.Store) {
	tick := time.NewTicker(time.Hour)
	defer tick.Stop()
	for {
		n, err := st.PurgeDeleted(ctx, time.Now().Add(-store.TrashRetention))
		if err != nil && ctx.Err() == nil {
			slog.Error("purge trash", "err", err)
		} else if n > 0 {
			slog.Info("purged deleted documents", "count", n)
		}
		n, err = st.PurgeImages(ctx, time.Now().Add(-store.ImageRetention))
		if err != nil && ctx.Err() == nil {
			slog.Error("purge images", "err", err)
		} else if n > 0 {
			slog.Info("purged unused images", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

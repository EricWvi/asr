// Package tidy turns spoken transcripts into written Markdown with a chat
// model behind any OpenAI-compatible chat completions API, streaming.
package tidy

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"strings"
)

// Client calls the chat completions API.
type Client struct {
	url, model, apiKey string
	extra              map[string]any
	http               *http.Client
}

// New returns a client for the API at baseURL, e.g.
// "https://api.deepseek.com"; requests go to <baseURL>/chat/completions.
// Extra fields are merged into every request body as they are, for vendor
// parameters such as DeepSeek's "thinking"; the standard fields win.
func New(baseURL, model, apiKey string, extra map[string]any) *Client {
	return &Client{
		url:    strings.TrimRight(baseURL, "/") + "/chat/completions",
		model:  model,
		apiKey: apiKey,
		extra:  extra,
		http:   &http.Client{},
	}
}

// Request is a stretch of dictated text and what surrounds it.
type Request struct {
	// Text is the transcript to tidy.
	Text string
	// Headings are the document's heading lines before the text, such as
	// "## 周末出行", so new headings fit under them.
	Headings []string
	// Prefix and Suffix are what precede and follow the text on its lines,
	// such as a list marker, when it starts or ends mid-line.
	Prefix, Suffix string
}

// ErrTruncated means the model stopped before finishing, e.g. at its
// output limit.
var ErrTruncated = errors.New("tidy: output truncated")

// StatusError is an error response from the API.
type StatusError struct {
	Code int
	Body string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("tidy: HTTP %d: %s", e.Code, e.Body)
}

// Stream asks for the tidied text and passes it to emit piece by piece as
// it arrives. It returns nil only if the model finished normally.
func (c *Client) Stream(ctx context.Context, req Request, emit func(string) error) error {
	body := maps.Clone(c.extra)
	if body == nil {
		body = map[string]any{}
	}
	body["model"] = c.model
	body["stream"] = true
	body["messages"] = []map[string]string{
		{"role": "system", "content": systemPrompt},
		{"role": "user", "content": userMessage(req)},
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("tidy: encode request: %w", err)
	}
	hr, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	hr.Header.Set("Content-Type", "application/json")
	hr.Header.Set("Accept", "text/event-stream")
	hr.Header.Set("Authorization", "Bearer "+c.apiKey)
	resp, err := c.http.Do(hr)
	if err != nil {
		return fmt.Errorf("tidy: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return &StatusError{Code: resp.StatusCode, Body: strings.TrimSpace(string(msg))}
	}

	// Server-sent events: "data: {chunk}" lines, ending with "data: [DONE]".
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	finished := false
	for sc.Scan() {
		data, ok := strings.CutPrefix(sc.Text(), "data:")
		if !ok {
			continue
		}
		data = strings.TrimSpace(data)
		if data == "[DONE]" {
			break
		}
		var chunk struct {
			Choices []struct {
				Delta struct {
					Content string `json:"content"`
				} `json:"delta"`
				FinishReason *string `json:"finish_reason"`
			} `json:"choices"`
		}
		if err := json.Unmarshal([]byte(data), &chunk); err != nil {
			return fmt.Errorf("tidy: decode chunk: %w", err)
		}
		for _, ch := range chunk.Choices {
			if ch.Delta.Content != "" {
				if err := emit(ch.Delta.Content); err != nil {
					return err
				}
			}
			if ch.FinishReason != nil {
				if *ch.FinishReason != "stop" {
					return fmt.Errorf("%w (%s)", ErrTruncated, *ch.FinishReason)
				}
				finished = true
			}
		}
	}
	if err := sc.Err(); err != nil {
		return fmt.Errorf("tidy: read stream: %w", err)
	}
	if !finished {
		return fmt.Errorf("tidy: stream ended without finishing")
	}
	return nil
}

const systemPrompt = `你是文字编辑。用户给你的是语音转写出的口语稿，请把它整理成规范的书面中文 Markdown。

要求：
- 保留全部信息和原意，不增加原文没有的内容，不做总结或评论。
- 去掉重复、口头语和说错后又改口的部分，以改口后的说法为准。
- 修正明显的同音错别字，理顺语序和标点。
- 按内容组织结构：并列的要点用列表，有先后的步骤用有序列表，提到要做的事用待办 ` + "`- [ ]`" + `，内容较长且有明显主题切换时分段或加标题。内容短时不要硬加结构。
- 只输出整理后的 Markdown 正文，不要解释，不要用代码块包裹。

口语稿在 <口语稿> 里。可能还附有两项只读的上下文，不要输出它们：
- <上文标题>：文稿中这段话之前的标题。需要加标题时，层级要和它们衔接：属于最后那个标题之下的内容，用比它低一级的标题；没有上文标题时从 ` + "`##`" + ` 开始。
- <行首>、<行尾>：这段话所在行前面、后面已有的文字，例如列表符号。整理结果会原样嵌在它们中间，所以有其中任何一项时，只输出一段不换行的文字，不加任何结构。`

func userMessage(req Request) string {
	var b strings.Builder
	if len(req.Headings) > 0 {
		b.WriteString("<上文标题>\n")
		for _, h := range req.Headings {
			b.WriteString(h + "\n")
		}
		b.WriteString("</上文标题>\n\n")
	}
	if req.Prefix != "" {
		b.WriteString("<行首>" + req.Prefix + "</行首>\n\n")
	}
	if req.Suffix != "" {
		b.WriteString("<行尾>" + req.Suffix + "</行尾>\n\n")
	}
	b.WriteString("<口语稿>\n" + req.Text + "\n</口语稿>")
	return b.String()
}

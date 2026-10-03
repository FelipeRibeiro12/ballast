package http

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthHandler(t *testing.T) {
	cases := []struct {
		name       string
		method     string
		wantStatus int
		wantBody   string
	}{
		{"GET responde ok", http.MethodGet, http.StatusOK, "ok"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			mux := NewRouter()
			req := httptest.NewRequest(c.method, "/healthz", nil)
			rec := httptest.NewRecorder()

			mux.ServeHTTP(rec, req)

			if rec.Code != c.wantStatus {
				t.Errorf("status = %d, want %d", rec.Code, c.wantStatus)
			}
			if got := rec.Body.String(); got != c.wantBody {
				t.Errorf("body = %q, want %q", got, c.wantBody)
			}
		})
	}
}

func TestHealthRejectsOtherMethods(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/healthz", nil)
	rec := httptest.NewRecorder()

	NewRouter().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusMethodNotAllowed)
	}
}

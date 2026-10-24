


if (!document['_vaadintheme_sentry_componentCss']) {
  
  document['_vaadintheme_sentry_componentCss'] = true;
}

if (import.meta.hot) {
  import.meta.hot.accept((module) => {
    window.location.reload();
  });
}


const path = require('node:path');
// Match the native stable logical URL while serving the actual deployment bytes.
function iconAsset(web, pathname) {
  if (/^\/chat-icons\/[0-9]{2}-[a-z]+(?:-[a-z]+)*\.png$/.test(pathname)) pathname = pathname.replace(/\.png$/, '.webp');
  return path.resolve(web, '.' + pathname);
}
module.exports = {iconAsset};

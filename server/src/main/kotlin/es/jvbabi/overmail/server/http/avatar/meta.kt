package es.jvbabi.overmail.server.http.avatar

import es.jvbabi.overmail.server.database.models.EmailAvatar

/**
 * Where the bytes of a picture sit. The id is part of it, which is what makes the answer cacheable
 * forever -- see `getAvatar`.
 */
fun avatarUrl(avatarId: EmailAvatar.Id): String = "/api/avatars/$avatarId"

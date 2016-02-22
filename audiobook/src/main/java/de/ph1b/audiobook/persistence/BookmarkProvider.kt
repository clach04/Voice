/*
 * This file is part of Material Audiobook Player.
 *
 * Material Audiobook Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * Material Audiobook Player is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Material Audiobook Player. If not, see <http://www.gnu.org/licenses/>.
 * /licenses/>.
 */

package de.ph1b.audiobook.persistence

import Slimber
import de.ph1b.audiobook.model.Book
import de.ph1b.audiobook.model.Bookmark
import de.ph1b.audiobook.persistence.internals.InternalBookmarkRegister
import rx.Observable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides access to bookmarks.
 *
 * @author: Paul Woitaschek
 */
@Singleton
class BookmarkProvider
@Inject constructor(private val register: InternalBookmarkRegister) {

    fun deleteBookmark(id: Long) = register.deleteBookmark(id)

    fun addBookmark(bookmark: Bookmark) = register.addBookmark(bookmark)

    fun addBookmarkAtBookPosition(book: Book, title: String) {
        val addedBookmark = Bookmark(book.currentChapter().file, title, book.time)
        addBookmark(addedBookmark)
        Slimber.v { "Added bookmark=$addedBookmark" }
    }

    fun bookmarks(book: Book) = Observable.fromCallable { register.bookmarks(book) }
}
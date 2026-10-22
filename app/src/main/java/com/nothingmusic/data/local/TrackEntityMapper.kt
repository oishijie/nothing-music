package com.nothingmusic.data.local

import android.net.Uri
import com.nothingmusic.data.local.entity.TrackEntity
import com.nothingmusic.data.scanner.SCANNER_METADATA_VERSION
import com.nothingmusic.domain.model.Track

internal fun TrackEntity.toDomain(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    discNumber = discNumber,
    durationMs = durationMs,
    path = uri,
    albumArtUri = artworkUri?.let(Uri::parse),
    folderPath = folderUri,
    dateAdded = dateAdded,
    trackNumber = trackNumber,
    year = year,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sampleRate = sampleRate,
    bitrate = bitrate,
    lastModified = lastModified,
    documentName = documentName,
    remoteId = remoteId.takeIf(String::isNotBlank),
)

internal fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    uri = path,
    documentName = documentName,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    discNumber = discNumber,
    durationMs = durationMs,
    folderUri = folderPath,
    dateAdded = dateAdded,
    trackNumber = trackNumber,
    year = year,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    sampleRate = sampleRate,
    bitrate = bitrate,
    artworkUri = albumArtUri?.toString(),
    metadataVersion = if (isRemote) 0 else SCANNER_METADATA_VERSION,
    remoteId = remoteId.orEmpty(),
)

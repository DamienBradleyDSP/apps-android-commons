package fr.free.nrw.commons

import android.os.Parcelable
import fr.free.nrw.commons.location.LatLng
import kotlinx.android.parcel.Parcelize
import org.wikipedia.dataclient.mwapi.MwQueryPage
import org.wikipedia.page.PageTitle
import java.util.*

@Parcelize
class Media constructor(
    /**
     * @return pageId for the current media object
     * Wikibase Identifier associated with media files
     */
    var pageId: String = UUID.randomUUID().toString(),
    var thumbUrl: String? = null,

    /**
     * Gets image URL
     * @return Image URL
     */
    var imageUrl: String? = null,
    /**
     * Gets the name of the file.
     * @return file name as a string
     */
    var filename: String? = null,
    /**
     * Gets the file description.
     * @return file description as a string
     */
    // monolingual description on input...
    /**
     * Sets the file description.
     * @param fallbackDescription the new description of the file
     */
    var fallbackDescription: String? = null,

    /**
     * Gets the upload date of the file.
     * Can be null.
     * @return upload date as a Date
     */
    var dateUploaded: Date? = null,
    /**
     * Gets the license name of the file.
     * @return license as a String
     */
    /**
     * Sets the license name of the file.
     *
     * @param license license name as a String
     */
    var license: String? = null,
    var licenseUrl: String? = null,
    /**
     * Gets the name of the creator of the file.
     * @return author name as a String
     */
    /**
     * Sets the author name of the file.
     * @param author creator name as a string
     */
    var author: String? = null,

    var user:String?=null,

    /**
     * Gets the categories the file falls under.
     * @return file categories as an ArrayList of Strings
     */
    var categories: List<String>? = null,
    /**
     * Gets the coordinates of where the file was created.
     * @return file coordinates as a LatLng
     */
    var coordinates: LatLng? = null,
    var captions: Map<String, String> = emptyMap(),
    var descriptions: Map<String, String> = emptyMap(),
    var depictionIds: List<String> = emptyList(),
    /**
     * This field was added to find non-hidden categories
     * Stores the mapping of category title to hidden attribute
     * Example: "Mountains" => false, "CC-BY-SA-2.0" => true
     */
    var categoriesHiddenStatus: Map<String, Boolean> = emptyMap()
) : Parcelable {

    constructor(
        captions: Map<String, String>,
        categories: List<String>?,
        filename: String?,
        fallbackDescription: String?,
        author: String?, user:String?
    ) : this(
        filename = filename,
        fallbackDescription = fallbackDescription,
        dateUploaded = Date(),
        author = author,
        user=user,
        categories = categories,
        captions = captions
    )

    /**
     * Gets media display title
     * @return Media title
     */
    val displayTitle: String
        get() =
            if (filename != null)
                pageTitle.displayTextWithoutNamespace.replaceFirst("[.][^.]+$".toRegex(), "")
            else
                ""

    /**
     * Gets media display title
     * @return Media title
     */
    fun getDisplayAuthor(): String{
        if (author == null)
            return ""

        val completeHtmlTagCheck = "<.*>(.*)<.*>".toRegex() // Detect if complete html tags present
        var completeCheckResult = completeHtmlTagCheck.findAll(author!!) // Find matching cases, return text between tag
        if(completeCheckResult.count()==1) return completeCheckResult.toList().get(0).groupValues.get(1) // Get matching result and return text between tag - text between tag is inside a capture group
        else if (completeCheckResult.count()>1) return "" // If there are multiple cases we return nothing --> can multiple cases occur?

        val hrefTagCheck = "<.*>(.*)".toRegex() // Detect if half html tags present -> <a href= XXX> case
        var hrefTagCheckResult = hrefTagCheck.findAll(author!!) // Find matching cases, return text between tag
        if(hrefTagCheckResult.count()==1) return hrefTagCheckResult.toList().get(0).groupValues.get(1) // Get matching result and return text between tag
        else if (hrefTagCheckResult.count()>1) return "" // If there are multiple cases we return nothing --> can multiple cases occur?

        return author!! // If this is reached then html tags are not present, return author text as is

    }

    /**
     * Gets file page title
     * @return New media page title
     */
    val pageTitle: PageTitle get() = Utils.getPageTitle(filename!!)

    /**
     * Returns wikicode to use the media file on a MediaWiki site
     * @return
     */
    val wikiCode: String
        get() = String.format("[[%s|thumb|%s]]", filename, mostRelevantCaption)

    val mostRelevantCaption: String
        get() = captions[Locale.getDefault().language]
            ?: captions.values.firstOrNull()
            ?: displayTitle

    /**
     * Gets the categories the file falls under.
     * @return file categories as an ArrayList of Strings
     */
    var addedCategories: List<String>? = null
        // TODO added categories should be removed. It is added for a short fix. On category update,
        //  categories should be re-fetched instead
        get() = field                     // getter
        set(value) { field = value }      // setter
}

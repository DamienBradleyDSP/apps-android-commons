package fr.free.nrw.commons.upload.mediaDetails;

import static fr.free.nrw.commons.di.CommonsApplicationModule.IO_THREAD;
import static fr.free.nrw.commons.di.CommonsApplicationModule.MAIN_THREAD;
import static fr.free.nrw.commons.utils.ImageUtils.EMPTY_CAPTION;
import static fr.free.nrw.commons.utils.ImageUtils.FILE_NAME_EXISTS;
import static fr.free.nrw.commons.utils.ImageUtils.IMAGE_KEEP;
import static fr.free.nrw.commons.utils.ImageUtils.IMAGE_OK;

import android.location.Address;
import android.location.Geocoder;
import androidx.annotation.Nullable;
import fr.free.nrw.commons.CommonsApplication;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.filepicker.UploadableFile;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.nearby.Place;
import fr.free.nrw.commons.repository.UploadRepository;
import fr.free.nrw.commons.upload.ImageCoordinates;
import fr.free.nrw.commons.upload.SimilarImageInterface;
import fr.free.nrw.commons.upload.UploadItem;
import fr.free.nrw.commons.upload.UploadMediaDetail;
import fr.free.nrw.commons.upload.mediaDetails.UploadMediaDetailsContract.UserActionListener;
import fr.free.nrw.commons.upload.mediaDetails.UploadMediaDetailsContract.View;
import io.reactivex.Maybe;
import io.reactivex.Scheduler;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Named;
import org.jetbrains.annotations.NotNull;
import timber.log.Timber;

public class UploadMediaPresenter implements UserActionListener, SimilarImageInterface {

    private static final UploadMediaDetailsContract.View DUMMY = (UploadMediaDetailsContract.View) Proxy
            .newProxyInstance(
                    UploadMediaDetailsContract.View.class.getClassLoader(),
                    new Class[]{UploadMediaDetailsContract.View.class},
                    (proxy, method, methodArgs) -> null);

    private final UploadRepository repository;
    private UploadMediaDetailsContract.View view = DUMMY;

    private CompositeDisposable compositeDisposable;

    private final JsonKvStore defaultKVStore;
    private Scheduler ioScheduler;
    private Scheduler mainThreadScheduler;

    private final List<String> WLM_SUPPORTED_COUNTRIES= Arrays.asList("am","at","az","br","hr","sv","fi","fr","de","gh","in","ie","il","mk","my","mt","pk","pe","pl","ru","rw","si","es","se","tw","ug","ua","us");

    @Inject
    public UploadMediaPresenter(UploadRepository uploadRepository,
        @Named("default_preferences") JsonKvStore defaultKVStore,
                                @Named(IO_THREAD) Scheduler ioScheduler,
                                @Named(MAIN_THREAD) Scheduler mainThreadScheduler) {
        this.repository = uploadRepository;
        this.defaultKVStore = defaultKVStore;
        this.ioScheduler = ioScheduler;
        this.mainThreadScheduler = mainThreadScheduler;
        compositeDisposable = new CompositeDisposable();
    }

    @Override
    public void onAttachView(View view) {
        this.view = view;
    }

    @Override
    public void onDetachView() {
        this.view = DUMMY;
        compositeDisposable.clear();
    }

    /**
     * Receives the corresponding uploadable file, processes it and return the view with and uplaod item
     *  @param uploadableFile
     * @param place
     */
    @Override
    public void receiveImage(final UploadableFile uploadableFile, final Place place) {
        view.showProgress(true);
        compositeDisposable.add(
            repository
                .preProcessImage(uploadableFile, place, this)
                .map(uploadItem -> {
                    if(place!=null && place.isMonument()){
                        if (place.location != null) {
                            final String countryCode = reverseGeoCode(place.location);
                            if (countryCode != null && WLM_SUPPORTED_COUNTRIES
                                .contains(countryCode.toLowerCase())) {
                                uploadItem.setWLMUpload(true);
                                uploadItem.setCountryCode(countryCode.toLowerCase());
                            }
                        }
                    }
                    return uploadItem;
                })
                .subscribeOn(ioScheduler)
                .observeOn(mainThreadScheduler)
                .subscribe(uploadItem ->
                    {
                        view.onImageProcessed(uploadItem, place);
                        view.updateMediaDetails(uploadItem.getUploadMediaDetails());
                        final ImageCoordinates gpsCoords = uploadItem.getGpsCoords();
                        final boolean hasImageCoordinates =
                          gpsCoords != null && gpsCoords.getImageCoordsExists();
                        view.showProgress(false);
                        if (hasImageCoordinates && place == null) {
                            checkNearbyPlaces(uploadItem);
                        }
                    },
                    throwable -> Timber.e(throwable, "Error occurred in processing images")));
    }

    @Nullable
    private String reverseGeoCode(final LatLng latLng){
        final Geocoder geocoder = new Geocoder(
            CommonsApplication.getInstance().getApplicationContext(), Locale
            .getDefault());
        try {
            final List<Address> addresses = geocoder
                .getFromLocation(latLng.getLatitude(), latLng.getLongitude(), 1);
            for (final Address address : addresses) {
                if (address != null && address.getCountryCode() != null) {
                    String countryCode = address.getCountryCode();
                    return countryCode;
                }
            }
        } catch (final IOException e) {
            Timber.e(e);
        }
        return null;
    }

    /**
     * This method checks for the nearest location that needs images and suggests it to the user.
     * @param uploadItem
     */
    private void checkNearbyPlaces(final UploadItem uploadItem) {
        final Disposable checkNearbyPlaces = Maybe.fromCallable(() -> repository
                .checkNearbyPlaces(uploadItem.getGpsCoords().getDecLatitude(),
                        uploadItem.getGpsCoords().getDecLongitude()))
                .subscribeOn(ioScheduler)
                .observeOn(mainThreadScheduler)
                .subscribe(place -> {
                        if (place != null) {
                            view.onNearbyPlaceFound(uploadItem, place);
                        }
                    },
                    throwable -> Timber.e(throwable, "Error occurred in processing images"));
            compositeDisposable.add(checkNearbyPlaces);
    }

    /**
     * asks the repository to verify image quality
     *
     * @param uploadItemIndex
     */
    @Override
    public void verifyImageQuality(int uploadItemIndex) {
      final UploadItem uploadItem = repository.getUploads().get(uploadItemIndex);

      if (uploadItem.getGpsCoords().getDecimalCoords() == null) {
          final Runnable onSkipClicked = () -> {
              view.showProgress(true);
              compositeDisposable.add(
                  repository
                      .getImageQuality(uploadItem)
                      .observeOn(mainThreadScheduler)
                      .subscribe(imageResult -> {
                              view.showProgress(false);
                              handleImageResult(imageResult, uploadItem);
                          },
                          throwable -> {
                              view.showProgress(false);
                              if (throwable instanceof UnknownHostException) {
                                  view.showConnectionErrorPopup();
                              } else {
                                  view.showMessage("" + throwable.getLocalizedMessage(),
                                      R.color.color_error);
                              }
                              Timber.e(throwable, "Error occurred while handling image");
                          })
              );
          };
          view.displayAddLocationDialog(onSkipClicked);
      } else {
          view.showProgress(true);
          compositeDisposable.add(
              repository
                  .getImageQuality(uploadItem)
                  .observeOn(mainThreadScheduler)
                  .subscribe(imageResult -> {
                          view.showProgress(false);
                          handleImageResult(imageResult, uploadItem);
                      },
                      throwable -> {
                          view.showProgress(false);
                          if (throwable instanceof UnknownHostException) {
                              view.showConnectionErrorPopup();
                          } else {
                              view.showMessage("" + throwable.getLocalizedMessage(),
                                  R.color.color_error);
                          }
                          Timber.e(throwable, "Error occurred while handling image");
                      })
          );
      }
    }


    /**
     * Copies the caption and description of the current item to the subsequent media
     *
     * @param indexInViewFlipper
     */
    @Override
    public void copyTitleAndDescriptionToSubsequentMedia(int indexInViewFlipper) {
      for(int i = indexInViewFlipper+1; i < repository.getCount(); i++){
        final UploadItem subsequentUploadItem = repository.getUploads().get(i);
        subsequentUploadItem.setMediaDetails(deepCopy(repository.getUploads().get(indexInViewFlipper).getUploadMediaDetails()));
      }
    }

  /**
   * Fetches and set the caption and description of the item
   *
   * @param indexInViewFlipper
   */
  @Override
  public void fetchTitleAndDescription(int indexInViewFlipper) {
    final UploadItem currentUploadItem = repository.getUploads().get(indexInViewFlipper);
    view.updateMediaDetails(currentUploadItem.getUploadMediaDetails());
  }

  @NotNull
  private List<UploadMediaDetail> deepCopy(List<UploadMediaDetail> uploadMediaDetails) {
    final ArrayList<UploadMediaDetail> newList = new ArrayList<>();
    for (UploadMediaDetail uploadMediaDetail : uploadMediaDetails) {
      newList.add(uploadMediaDetail.javaCopy());
    }
    return newList;
  }

  @Override
  public void useSimilarPictureCoordinates(ImageCoordinates imageCoordinates, int uploadItemIndex) {
    repository.useSimilarPictureCoordinates(imageCoordinates, uploadItemIndex);
  }

  @Override
  public void onMapIconClicked(int indexInViewFlipper) {
    view.showExternalMap(repository.getUploads().get(indexInViewFlipper));
  }

  @Override
  public void onUserConfirmedUploadIsOfPlace(Place place, int uploadItemPosition) {
    final List<UploadMediaDetail> uploadMediaDetails = repository.getUploads()
        .get(uploadItemPosition)
        .getUploadMediaDetails();
    UploadItem uploadItem = repository.getUploads()
        .get(uploadItemPosition);
    uploadItem.setPlace(place);
    uploadMediaDetails.set(0, new UploadMediaDetail(place));
    view.updateMediaDetails(uploadMediaDetails);
  }

  /**
     * handles image quality verifications
     *
   * @param imageResult
   * @param uploadItem
   */
    public void handleImageResult(Integer imageResult,
        UploadItem uploadItem) {
        if (imageResult == IMAGE_KEEP || imageResult == IMAGE_OK) {
            view.onImageValidationSuccess();
            uploadItem.setHasInvalidLocation(false);
        } else {
            handleBadImage(imageResult, uploadItem);
        }
    }

    /**
     * Handle  images, say empty caption, duplicate file name, bad picture(in all other cases)
     *
     * @param errorCode
     * @param uploadItem
     */
    public void handleBadImage(Integer errorCode,
        UploadItem uploadItem) {
        Timber.d("Handle bad picture with error code %d", errorCode);
        if (errorCode
            >= 8) { // If location of image and nearby does not match
            uploadItem.setHasInvalidLocation(true);
        }

        switch (errorCode) {
            case EMPTY_CAPTION:
                Timber.d("Captions are empty. Showing toast");
                view.showMessage(R.string.add_caption_toast, R.color.color_error);
                break;
            case FILE_NAME_EXISTS:
                Timber.d("Trying to show duplicate picture popup");
                view.showDuplicatePicturePopup(uploadItem);
                break;
            default:
                view.showBadImagePopup(errorCode, uploadItem);
        }
    }

    /**
     * notifies the user that a similar image exists
     * @param originalFilePath
     * @param possibleFilePath
     * @param similarImageCoordinates
     */
    @Override
    public void showSimilarImageFragment(String originalFilePath, String possibleFilePath,
        ImageCoordinates similarImageCoordinates) {
        view.showSimilarImageFragment(originalFilePath, possibleFilePath,
            similarImageCoordinates
        );
    }
}

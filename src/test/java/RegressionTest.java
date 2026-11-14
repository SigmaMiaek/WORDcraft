import org.junit.jupiter.api.Test;

/** Run the original regression fixtures against classes compiled from source. */
class RegressionTest {
    @Test void videoAnswers() throws Exception { VideoAnswerTest.main(new String[0]); }
    @Test void videoAnswerUi() throws Exception { VideoAnswerUiTest.main(new String[0]); }
    @Test void mediaFallback() throws Exception { MediaFallbackTest.main(new String[0]); }
    @Test void mediaFallbackUi() throws Exception { MediaFallbackUiTest.main(new String[0]); }
    @Test void bulkDownload() throws Exception { BulkDownloadTest.main(new String[0]); }
    @Test void bulkHttpRange() throws Exception { BulkRangeTransferTest.main(new String[0]); }
    @Test void bulkInventoryUi() throws Exception { BulkInventoryUiTest.main(new String[0]); }
    @Test void downloadDisplay() { DownloadDisplayTest.main(new String[0]); }
}
